/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.tdunning.math.stats;

import com.google.common.base.Preconditions;
import com.google.common.collect.Iterables;
import com.google.common.collect.Lists;

import java.nio.ByteBuffer;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.Random;

/**
 * Adaptive histogram based on something like streaming k-means crossed with Q-digest.
 * <p/>
 * The special characteristics of this algorithm are:
 * <p/>
 * a) smaller summaries than Q-digest
 * <p/>
 * b) works on doubles as well as integers.
 * <p/>
 * c) provides part per million accuracy for extreme quantiles and typically <1000 ppm accuracy for middle quantiles
 * <p/>
 * d) fast
 * <p/>
 * e) simple
 * <p/>
 * f) test coverage > 90%
 * <p/>
 * g) easy to adapt for use with map-reduce
 */
public class TreeDigest extends TDigest {

    private double compression = 100;
    private GroupTree summary = new GroupTree();
    int count = 0; // package private for testing

    /**
     * A histogram structure that will record a sketch of a distribution.
     *
     * @param compression How should accuracy be traded for size?  A value of N here will give quantile errors
     *                    almost always less than 3/N with considerably smaller errors expected for extreme
     *                    quantiles.  Conversely, you should expect to track about 5 N centroids for this
     *                    accuracy.
     */
    public TreeDigest(double compression) {
        this.compression = compression;
    }

    @Override
    public void add(double x, int w) {
            // note that because of a zero id, this will be sorted *before* any existing Centroid with the same mean
            add(x, w, createCentroid(x, 0));
    }

    @Override
    public void add(double x, int w, Centroid base) {
        Centroid start = summary.floor(base);
        if (start == null) {
            start = summary.ceiling(base);
        }

        if (start == null) {
            summary.add(Centroid.createWeighted(x, w, base.data()));
            count = w;
        } else {
            Iterable<Centroid> neighbors = summary.tailSet(start);
            double minDistance = Double.MAX_VALUE;
            int lastNeighbor = 0;
            int i = summary.headCount(start);
            for (Centroid neighbor : neighbors) {
                double z = Math.abs(neighbor.mean() - x);
                if (z <= minDistance) {
                    minDistance = z;
                    lastNeighbor = i;
                } else {
                    // as soon as z increases, we have passed the nearest neighbor and can quit
                    break;
                }
                i++;
            }

            Centroid closest = null;
            int sum = summary.headSum(start);
            i = summary.headCount(start);
            double n = 1;
            for (Centroid neighbor : neighbors) {
                if (i > lastNeighbor) {
                    break;
                }
                double z = Math.abs(neighbor.mean() - x);
                double q = (sum + neighbor.count() / 2.0) / count;
                double k = 4 * count * q * (1 - q) / compression;

                // this slightly clever selection method improves accuracy with lots of repeated points
                if (z == minDistance && neighbor.count() + w <= k) {
                    if (gen.nextDouble() < 1 / n) {
                        closest = neighbor;
                    }
                    n++;
                }
                sum += neighbor.count();
                i++;
            }

            if (closest == null) {
                summary.add(Centroid.createWeighted(x, w, base.data()));
            } else {
                // if the nearest point was not unique, then we may not be modifying the first copy
                // which means that ordering can change
                summary.remove(closest);
                closest.add(x, w, base.data());
                summary.add(closest);
            }
            count += w;

            if (summary.size() > 100 * compression) {
                // something such as sequential ordering of data points
                // has caused a pathological expansion of our summary.
                // To fight this, we simply replay the current centroids
                // in random order.

                // this causes us to forget the diagnostic recording of data points
                compress();
            }
        }
    }

    public static TreeDigest merge(double compression, Iterable<TDigest> subData, Random gen) {
        List<Centroid> centroids = Lists.newArrayList();
        boolean recordAll = false;
        for (TDigest digest : subData) {
            Iterables.addAll(centroids, digest.centroids());
            recordAll |= digest.isRecording();
        }
        Collections.shuffle(centroids, gen);
        TreeDigest r = new TreeDigest(compression);
        if (recordAll) {
            r.recordAllData();
        }

        for (Centroid c : centroids) {
            if (r.recordAllData) {
                // TODO should do something better here.
            }
            r.add(c.mean(), c.count(), c);
        }
        return r;
    }

    @Override
    public void compress() {
        compress(summary);
    }

    @Override
    public void compress(GroupTree other) {
        TreeDigest reduced = new TreeDigest(compression);
        if (recordAllData) {
            reduced.recordAllData();
        }
        List<Centroid> tmp = Lists.newArrayList(other);
        Collections.shuffle(tmp, gen);
        for (Centroid centroid : tmp) {
            reduced.add(centroid.mean(), centroid.count(), centroid);
        }

        summary = reduced.summary;
    }

    /**
     * Returns the number of samples represented in this histogram.  If you want to know how many
     * centroids are being used, try centroids().size().
     *
     * @return the number of samples that have been added.
     */
    @Override
    public int size() {
        return count;
    }

    /**
     * @param x the value at which the CDF should be evaluated
     * @return the approximate fraction of all samples that were less than or equal to x.
     */
    @Override
    public double cdf(double x) {
        GroupTree values = summary;
        if (values.size() == 0) {
            return Double.NaN;
        } else if (values.size() == 1) {
            return x < values.first().mean() ? 0 : 1;
        } else {
            double r = 0;

            // we scan a across the centroids
            Iterator<Centroid> it = values.iterator();
            Centroid a = it.next();

            // b is the look-ahead to the next centroid
            Centroid b = it.next();

            // initially, we set left width equal to right width
            double left = (b.mean() - a.mean()) / 2;
            double right = left;

            // scan to next to last element
            while (it.hasNext()) {
                if (x < a.mean() + right) {
                    return (r + a.count() * interpolate(x, a.mean() - left, a.mean() + right)) / count;
                }
                r += a.count();

                a = b;
                b = it.next();

                left = right;
                right = (b.mean() - a.mean()) / 2;
            }

            // for the last element, assume right width is same as left
            left = right;
            a = b;
            if (x < a.mean() + right) {
                return (r + a.count() * interpolate(x, a.mean() - left, a.mean() + right)) / count;
            } else {
                return 1;
            }
        }
    }

    /**
     * @param q The quantile desired.  Can be in the range [0,1].
     * @return The minimum value x such that we think that the proportion of samples is <= x is q.
     */
    @Override
    public double quantile(double q) {
        GroupTree values = summary;
        Preconditions.checkArgument(values.size() > 1);

        Iterator<Centroid> it = values.iterator();
        Centroid a = it.next();
        Centroid b = it.next();
        if (!it.hasNext()) {
            // both a and b have to have just a single element
            double diff = (b.mean() - a.mean()) / 2;
            if (q > 0.75) {
                return b.mean() + diff * (4 * q - 3);
            } else {
                return a.mean() + diff * (4 * q - 1);
            }
        } else {
            q *= count;
            double right = (b.mean() - a.mean()) / 2;
            // we have nothing else to go on so make left hanging width same as right to start
            double left = right;

            if (q <= a.count()) {
                return a.mean() + left * (2 * q - a.count()) / a.count();
            } else {
                double t = a.count();
                while (it.hasNext()) {
                    if (t + b.count() / 2 >= q) {
                        // left of b
                        return b.mean() - left * 2 * (q - t) / b.count();
                    } else if (t + b.count() >= q) {
                        // right of b but left of the left-most thing beyond
                        return b.mean() + right * 2 * (q - t - b.count() / 2.0) / b.count();
                    }
                    t += b.count();

                    a = b;
                    b = it.next();
                    left = right;
                    right = (b.mean() - a.mean()) / 2;
                }
                // shouldn't be possible but we have an answer anyway
                return b.mean() + right;
            }
        }
    }

    @Override
    public int centroidCount() {
        return summary.size();
    }

    @Override
    public Iterable<? extends Centroid> centroids() {
        return summary;
    }

    @Override
    public double compression() {
        return compression;
    }

    /**
     * Returns an upper bound on the number bytes that will be required to represent this histogram.
     */
    @Override
    public int byteSize() {
        return 4 + 8 + 4 + summary.size() * 12;
    }

    /**
     * Returns an upper bound on the number of bytes that will be required to represent this histogram in
     * the tighter representation.
     */
    @Override
    public int smallByteSize() {
        int bound = byteSize();
        ByteBuffer buf = ByteBuffer.allocate(bound);
        asSmallBytes(buf);
        return buf.position();
    }

    public final static int VERBOSE_ENCODING = 1;
    public final static int SMALL_ENCODING = 2;

    /**
     * Outputs a histogram as bytes using a particularly cheesy encoding.
     */
    @Override
    public void asBytes(ByteBuffer buf) {
        buf.putInt(VERBOSE_ENCODING);
        buf.putDouble(compression());
        buf.putInt(summary.size());
        for (Centroid centroid : summary) {
            buf.putDouble(centroid.mean());
        }

        for (Centroid centroid : summary) {
            buf.putInt(centroid.count());
        }
    }

    @Override
    public void asSmallBytes(ByteBuffer buf) {
        buf.putInt(SMALL_ENCODING);
        buf.putDouble(compression());
        buf.putInt(summary.size());

        double x = 0;
        for (Centroid centroid : summary) {
            double delta = centroid.mean() - x;
            x = centroid.mean();
            buf.putFloat((float) delta);
        }

        for (Centroid centroid : summary) {
            int n = centroid.count();
            encode(buf, n);
        }
    }

    public static void encode(ByteBuffer buf, int n) {
        int k = 0;
        while (n < 0 || n > 0x7f) {
            byte b = (byte) (0x80 | (0x7f & n));
            buf.put(b);
            n = n >>> 7;
            k++;
            Preconditions.checkState(k < 6);
        }
        buf.put((byte) n);
    }

    public static int decode(ByteBuffer buf) {
        int v = buf.get();
        int z = 0x7f & v;
        int shift = 7;
        while ((v & 0x80) != 0) {
            Preconditions.checkState(shift <= 28);
            v = buf.get();
            z += (v & 0x7f) << shift;
            shift += 7;
        }
        return z;
    }

    /**
     * Reads a histogram from a byte buffer
     *
     * @return The new histogram structure
     */
    public static TreeDigest fromBytes(ByteBuffer buf) {
        int encoding = buf.getInt();
        if (encoding == VERBOSE_ENCODING) {
            double compression = buf.getDouble();
            TreeDigest r = new TreeDigest(compression);
            int n = buf.getInt();
            double[] means = new double[n];
            for (int i = 0; i < n; i++) {
                means[i] = buf.getDouble();
            }
            for (int i = 0; i < n; i++) {
                r.add(means[i], buf.getInt());
            }
            return r;
        } else if (encoding == SMALL_ENCODING) {
            double compression = buf.getDouble();
            TreeDigest r = new TreeDigest(compression);
            int n = buf.getInt();
            double[] means = new double[n];
            double x = 0;
            for (int i = 0; i < n; i++) {
                double delta = buf.getFloat();
                x += delta;
                means[i] = x;
            }

            for (int i = 0; i < n; i++) {
                int z = decode(buf);
                r.add(means[i], z);
            }
            return r;
        } else {
            throw new IllegalStateException("Invalid format for serialized histogram");
        }
    }

    private double interpolate(double x, double x0, double x1) {
        return (x - x0) / (x1 - x0);
    }

}