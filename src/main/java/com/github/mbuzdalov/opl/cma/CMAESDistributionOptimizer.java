package com.github.mbuzdalov.opl.cma;

import java.util.Arrays;
import java.util.function.Function;

import org.apache.commons.math3.exception.NotStrictlyPositiveException;
import org.apache.commons.math3.linear.Array2DRowRealMatrix;
import org.apache.commons.math3.linear.EigenDecomposition;
import org.apache.commons.math3.linear.MatrixUtils;
import org.apache.commons.math3.linear.RealMatrix;
import org.apache.commons.math3.optim.PointValuePair;
import org.apache.commons.math3.random.RandomGenerator;
import org.apache.commons.math3.util.FastMath;
import org.apache.commons.math3.util.MathArrays;

public class CMAESDistributionOptimizer {
    // Main user input parameters.
    private final int populationSize;
    private final int dimension;
    private final Function<double[][], double[]> function;

    // Other configuration parameters.
    private final boolean isActiveCMA;
    private final int nResamplingUntilFeasible;
    private final int nDiagonalOnlyIterations;
    private final int maxIterations;

    // Internal termination criteria.
    private final double stopTolUpX;
    private final double stopTolX;
    private final double stopTolFun;
    private final double stopTolHistFun;

    // Fixed auto-inferred parameters.
    private final int mu;
    private final RealMatrix weights;
    private final double mueff;
    private final double cc;
    private final double cs;
    private final double damps;
    private final double ccov1;
    private final double ccovmu;
    private final double chiN;
    private final double ccov1Sep;
    private final double ccovmuSep;

    // Number of iterations done so far.
    private int iterations;

    // Varying auto-inferred parameters.
    private double sigma;
    private double normps;

    // Vectors and matrices.
    private RealMatrix xmean;
    private RealMatrix pc;
    private RealMatrix ps;
    private RealMatrix B;
    private RealMatrix D;
    private RealMatrix BD;
    private RealMatrix diagD;
    private RealMatrix C;
    private RealMatrix diagC;

    /** History queue of best values. */
    private final FitnessHistory fitnessHistory;

    /** Random generator. */
    private final RandomGenerator random;

    public CMAESDistributionOptimizer(int maxIterations,
                                      boolean isActiveCMA,
                                      int nDiagonalOnlyIterations,
                                      int nResamplingUntilFeasible,
                                      RandomGenerator random,
                                      int dimension,
                                      int populationSize,
                                      Function<double[][], double[]> function) {
        if (populationSize <= 0) {
            throw new NotStrictlyPositiveException(populationSize);
        }

        this.maxIterations = maxIterations;
        this.isActiveCMA = isActiveCMA;
        this.nDiagonalOnlyIterations = nDiagonalOnlyIterations;
        this.nResamplingUntilFeasible = nResamplingUntilFeasible;
        this.random = random;
        this.dimension = dimension;
        this.populationSize = populationSize;
        this.function = function;

        this.sigma = 1;

        // initialize termination criteria
        this.stopTolUpX = 1e3;
        this.stopTolX = 1e-11;
        this.stopTolFun = 1e-12;
        this.stopTolHistFun = 1e-13;

        // initialize selection strategy parameters
        this.mu = populationSize / 2;
        RealMatrix rawWeights = log(naturals(mu)).scalarMultiply(-1).scalarAdd(FastMath.log(mu + 0.5));
        double sumW = 0;
        double sumWQ = 0;
        for (int i = 0; i < mu; i++) {
            double w = rawWeights.getEntry(i, 0);
            sumW += w;
            sumWQ += w * w;
        }
        this.weights = rawWeights.scalarMultiply(1 / sumW);
        this.mueff = sumW * sumW / sumWQ;

        // initialize parameters and constants
        this.cc = (4 + mueff / dimension) / (dimension + 4 + 2 * mueff / dimension);
        this.cs = (mueff + 2) / (dimension + mueff + 3);
        this.damps = (1 + 2 * FastMath.max(0, FastMath.sqrt((mueff - 1) / (dimension + 1)) - 1)) *
                FastMath.max(0.3, 1 - dimension / (1e-6 + maxIterations)) + cs;
        this.ccov1 = 2 / ((dimension + 1.3) * (dimension + 1.3) + mueff);
        this.ccovmu = FastMath.min(1 - ccov1, 2 * (mueff - 2 + 1 / mueff) / ((dimension + 2) * (dimension + 2) + mueff));
        this.ccov1Sep = FastMath.min(1, ccov1 * (dimension + 1.5) / 3);
        this.ccovmuSep = FastMath.min(1 - ccov1, ccovmu * (dimension + 1.5) / 3);
        this.chiN = FastMath.sqrt(dimension) * (1 - 1 / (4.0 * dimension) + 1 / (21.0 * dimension * dimension));

        // initialize matrices and vectors that change
        diagD = columnOfOnes(dimension).scalarMultiply(1 / sigma);
        diagC = square(diagD);
        pc = zeros(dimension, 1);
        ps = zeros(dimension, 1);
        normps = ps.getFrobeniusNorm();
        B = eye(dimension, dimension);
        D = columnOfOnes(dimension);
        BD = times(B, replicateMatrix(diagD.transpose(), dimension, 1));
        C = B.multiply(diagonal(square(D)).multiply(B.transpose()));

        int historySize = 10 + (int) (3 * 10 * dimension / (double) populationSize);
        this.fitnessHistory = new FitnessHistory(historySize);
    }

    public PointValuePair optimize() {
        iterations = 0;
        double[] guess = generateNormalizedRandomVector();
        double[] fixedGuess = repair(guess);
        double bestValue = function.apply(new double[][] {fixedGuess})[0] + penalty(guess, fixedGuess);
        xmean = MatrixUtils.createColumnRealMatrix(guess);
        fitnessHistory.push(bestValue);
        PointValuePair optimum = new PointValuePair(guess, bestValue);

        // -------------------- Generation Loop --------------------------------

        for (iterations = 1; iterations <= maxIterations; iterations++) {
            // Generate and evaluate lambda offspring
            final RealMatrix arz = gaussianMatrix(dimension, populationSize);
            final RealMatrix arx = zeros(dimension, populationSize);
            final double[] fitness = new double[populationSize];
            final double[][] fixedIndividuals = new double[populationSize][];
            final double[] penalties = new double[populationSize];

            // generate random offspring
            for (int k = 0; k < populationSize; k++) {
                RealMatrix arxk = null;
                for (int i = 0; i <= nResamplingUntilFeasible; i++) {
                    if (nDiagonalOnlyIterations <= iterations) {
                        arxk = xmean.add(BD.multiply(arz.getColumnMatrix(k)).scalarMultiply(sigma));
                    } else {
                        arxk = xmean.add(times(diagD,arz.getColumnMatrix(k)).scalarMultiply(sigma));
                    }
                    if (i >= nResamplingUntilFeasible || isFeasible(arxk.getColumn(0))) {
                        break;
                    }
                    // regenerate random arguments for row
                    arz.setColumn(k, gaussianArray(dimension));
                }
                copyZeroColumn(arxk, arx, k);
                double[] rawIndividual = arx.getColumn(k);
                fixedIndividuals[k] = repair(rawIndividual);
                penalties[k] = penalty(rawIndividual, fixedIndividuals[k]);
            }

            double[] rawFitness = function.apply(fixedIndividuals);
            double valueRange = max(rawFitness) - min(rawFitness);
            for (int i = 0; i < populationSize; ++i) {
                fitness[i] = rawFitness[i] + penalties[i] * valueRange;
            }

            // Sort by fitness and compute weighted mean into xmean
            final int[] arindex = sortedIndices(fitness);
            // Calculate new xmean, this is selection and recombination
            final RealMatrix xold = xmean; // for speed up of Eq. (2) and (3)
            final RealMatrix bestArx = selectColumns(arx, MathArrays.copyOf(arindex, mu));
            xmean = bestArx.multiply(weights);
            final RealMatrix bestArz = selectColumns(arz, MathArrays.copyOf(arindex, mu));
            final RealMatrix zmean = bestArz.multiply(weights);
            final boolean hsig = updateEvolutionPaths(zmean, xold);
            if (nDiagonalOnlyIterations <= iterations) {
                updateCovariance(hsig, bestArx, arz, arindex, xold);
            } else {
                updateCovarianceDiagonalOnly(hsig, bestArz);
            }
            // Adapt step size sigma - Eq. (5)
            sigma *= FastMath.exp(FastMath.min(1, (normps/chiN - 1) * cs / damps));
            final double bestFitness = fitness[arindex[0]];
            final double worstFitness = fitness[arindex[arindex.length - 1]];
            if (bestValue > bestFitness) {
                bestValue = bestFitness;
                optimum = new PointValuePair(repair(bestArx.getColumn(0)), bestFitness);
            }
            // handle termination criteria
            if (shallExitBySigmaTolerance(pc.getColumn(0), sqrt(diagC).getColumn(0))) {
                break;
            }
            final double historyBest = fitnessHistory.getMinimum();
            final double historyWorst = fitnessHistory.getMaximum();
            if (iterations > 2 && Math.max(historyWorst, worstFitness) - Math.min(historyBest, bestFitness) < stopTolFun) {
                break;
            }
            if (iterations > fitnessHistory.getCapacity() && historyWorst - historyBest < stopTolHistFun) {
                break;
            }
            // condition number of the covariance matrix exceeds 1e14
            if (max(diagD) / min(diagD) > 1e7) {
                break;
            }
            // Adjust step size in case of equal function values (flat fitness)
            if (bestValue == fitness[arindex[(int) (0.1 + populationSize / 4.0)]]) {
                sigma *= FastMath.exp(0.2 + cs / damps);
            }
            if (iterations > 2 && FastMath.max(historyWorst, bestFitness) - FastMath.min(historyBest, bestFitness) == 0) {
                sigma *= FastMath.exp(0.2 + cs / damps);
            }
            // store best in history
            fitnessHistory.push(bestFitness);
        }
        return optimum;
    }

    private boolean shallExitBySigmaTolerance(double[] pcCol, double[] sqrtDiagC) {
        for (int i = 0; i < dimension; i++) {
            if (sigma * sqrtDiagC[i] > stopTolUpX) {
                return true;
            }
        }
        for (int i = 0; i < dimension; i++) {
            if (sigma * FastMath.max(FastMath.abs(pcCol[i]), sqrtDiagC[i]) > stopTolX) {
                return false;
            }
        }
        return true;
    }

    private double[] generateNormalizedRandomVector() {
        final double[] guess = new double[dimension];
        double guessSum = 0;
        for (int i = 0; i < dimension; ++i) {
            guess[i] = random.nextDouble();
            guessSum += guess[i];
        }
        for (int i = 0; i < dimension; ++i) {
            guess[i] /= guessSum;
        }
        return guess;
    }

    private boolean updateEvolutionPaths(RealMatrix zmean, RealMatrix xold) {
        ps = ps.scalarMultiply(1 - cs).add(B.multiply(zmean).scalarMultiply(FastMath.sqrt(cs * (2 - cs) * mueff)));
        normps = ps.getFrobeniusNorm();
        final boolean hSig = normps / FastMath.sqrt(1 - FastMath.pow(1 - cs, 2 * iterations)) / chiN
                < 1.4 + 2 / (dimension + 1.0);
        pc = pc.scalarMultiply(1 - cc);
        if (hSig) {
            pc = pc.add(xmean.subtract(xold).scalarMultiply(FastMath.sqrt(cc * (2 - cc) * mueff) / sigma));
        }
        return hSig;
    }

    private void updateCovarianceDiagonalOnly(boolean hSig, final RealMatrix bestArz) {
        double oldFac = hSig ? 0 : ccov1Sep * cc * (2 - cc);
        oldFac += 1 - ccov1Sep - ccovmuSep;
        diagC = diagC.scalarMultiply(oldFac) // regard old matrix
                .add(square(pc).scalarMultiply(ccov1Sep)) // plus rank one update
                .add((times(diagC, square(bestArz).multiply(weights))) // plus rank mu update
                        .scalarMultiply(ccovmuSep));
        diagD = sqrt(diagC); // replaces eig(C)
        if (iterations > nDiagonalOnlyIterations) {
            // full covariance matrix from now on
            B = eye(dimension, dimension);
            BD = diagonal(diagD);
            C = diagonal(diagC);
        }
    }

    private void updateCovariance(boolean hsig, final RealMatrix bestArx,
                                  final RealMatrix arz, final int[] arindex,
                                  final RealMatrix xold) {
        double negccov = 0;
        if (ccov1 + ccovmu > 0) {
            final RealMatrix arpos = bestArx.subtract(replicateMatrix(xold, 1, mu)).scalarMultiply(1 / sigma);
            final RealMatrix roneu = pc.multiply(pc.transpose()).scalarMultiply(ccov1);
            // minor correction if hsig==false
            double oldFac = hsig ? 0 : ccov1 * cc * (2 - cc);
            oldFac += 1 - ccov1 - ccovmu;
            if (isActiveCMA) {
                // Adapt covariance matrix C active CMA
                negccov = (1 - ccovmu) * 0.25 * mueff /
                        (FastMath.pow(dimension + 2, 1.5) + 2 * mueff);
                // keep at least 0.66 in all directions, small popsize are most
                // critical
                final double negminresidualvariance = 0.66;
                // where to make up for the variance loss
                final double negalphaold = 0.5;
                // prepare vectors, compute negative updating matrix Cneg
                final int[] arReverseIndex = reverse(arindex);
                RealMatrix arzneg = selectColumns(arz, MathArrays.copyOf(arReverseIndex, mu));
                RealMatrix arnorms = sqrt(sumRows(square(arzneg)));
                final int[] idxnorms = sortedIndices(arnorms.getRow(0));
                final RealMatrix arnormsSorted = selectColumns(arnorms, idxnorms);
                final int[] idxReverse = reverse(idxnorms);
                final RealMatrix arnormsReverse = selectColumns(arnorms, idxReverse);
                arnorms = divide(arnormsReverse, arnormsSorted);
                final int[] idxInv = inverse(idxnorms);
                final RealMatrix arnormsInv = selectColumns(arnorms, idxInv);
                // check and set learning rate negccov
                final double negcovMax = (1 - negminresidualvariance) /
                        square(arnormsInv).multiply(weights).getEntry(0, 0);
                if (negccov > negcovMax) {
                    negccov = negcovMax;
                }
                arzneg = times(arzneg, replicateMatrix(arnormsInv, dimension, 1));
                final RealMatrix artmp = BD.multiply(arzneg);
                final RealMatrix Cneg = artmp.multiply(diagonal(weights)).multiply(artmp.transpose());
                oldFac += negalphaold * negccov;
                C = C.scalarMultiply(oldFac)
                        .add(roneu) // regard old matrix
                        .add(arpos.scalarMultiply( // plus rank one update
                                ccovmu + (1 - negalphaold) * negccov) // plus rank mu update
                                .multiply(times(replicateMatrix(weights, 1, dimension),
                                        arpos.transpose())))
                        .subtract(Cneg.scalarMultiply(negccov));
            } else {
                // Adapt covariance matrix C - nonactive
                C = C.scalarMultiply(oldFac) // regard old matrix
                        .add(roneu) // plus rank one update
                        .add(arpos.scalarMultiply(ccovmu) // plus rank mu update
                                .multiply(times(replicateMatrix(weights, 1, dimension),
                                        arpos.transpose())));
            }
        }
        updateBD(negccov);
    }

    private void updateBD(double negccov) {
        if (ccov1 + ccovmu + negccov > 0 && (iterations % 1. / (ccov1 + ccovmu + negccov) / dimension / 10.0) < 1) {
            // to achieve O(N^2)
            C = upperTriangular(C, 0).add(upperTriangular(C, 1).transpose());
            // enforce symmetry to prevent complex numbers
            final EigenDecomposition eig = new EigenDecomposition(C);
            B = eig.getV(); // eigen decomposition, B==normalized eigenvectors
            D = eig.getD();
            diagD = diagonal(D);
            if (min(diagD) <= 0) {
                for (int i = 0; i < dimension; i++) {
                    if (diagD.getEntry(i, 0) < 0) {
                        diagD.setEntry(i, 0, 0);
                    }
                }
                final double tfac = max(diagD) / 1e14;
                C = C.add(eye(dimension, dimension).scalarMultiply(tfac));
                diagD = diagD.add(columnOfOnes(dimension).scalarMultiply(tfac));
            }
            if (max(diagD) > 1e14 * min(diagD)) {
                final double tfac = max(diagD) / 1e14 - min(diagD);
                C = C.add(eye(dimension, dimension).scalarMultiply(tfac));
                diagD = diagD.add(columnOfOnes(dimension).scalarMultiply(tfac));
            }
            diagC = diagonal(C);
            diagD = sqrt(diagD); // D contains standard deviations now
            BD = times(B, replicateMatrix(diagD.transpose(), dimension, 1)); // O(n^2)
        }
    }

    private static int[] sortedIndices(final double[] doubles) {
        final DoubleIndex[] dis = new DoubleIndex[doubles.length];
        for (int i = 0; i < doubles.length; i++) {
            dis[i] = new DoubleIndex(doubles[i], i);
        }
        Arrays.sort(dis);
        final int[] indices = new int[doubles.length];
        for (int i = 0; i < doubles.length; i++) {
            indices[i] = dis[i].index;
        }
        return indices;
    }

    private static class DoubleIndex implements Comparable<DoubleIndex> {
        private final double value;
        private final int index;

        DoubleIndex(double value, int index) {
            this.value = value;
            this.index = index;
        }

        public int compareTo(DoubleIndex o) {
            return Double.compare(value, o.value);
        }
    }

    private static boolean isFeasible(final double[] x) {
        for (double v : x) {
            if (v < 0 || v > 1) {
                return false;
            }
        }
        return true;
    }

    private static double[] repair(final double[] x) {
        final double[] repaired = x.clone();
        for (int i = 0; i < repaired.length; i++) {
            if (repaired[i] < 0) {
                repaired[i] = 0;
            } else if (repaired[i] > 1) {
                repaired[i] = 1;
            }
        }
        return repaired;
    }

    private static double penalty(final double[] x, final double[] repaired) {
        double penalty = 0;
        for (int i = 0; i < x.length; i++) {
            double diff = FastMath.abs(x[i] - repaired[i]);
            penalty += diff;
        }
        return penalty;
    }

    private static class FitnessHistory {
        private final double[] inputStack, outputMin, outputMax;
        private int nInputs, nOutputs;
        private double inputMin, inputMax;

        FitnessHistory(int length) {
            inputStack = new double[length];
            outputMin = new double[length];
            outputMax = new double[length];
            inputMin = Double.POSITIVE_INFINITY;
            inputMax = Double.NEGATIVE_INFINITY;
        }

        void push(double value) {
            if (nInputs + nOutputs == getCapacity()) {
                if (nOutputs == 0) {
                    double tailMin = Double.POSITIVE_INFINITY;
                    double tailMax = Double.NEGATIVE_INFINITY;
                    while (nInputs > 0) {
                        --nInputs;
                        double popped = inputStack[nInputs];
                        tailMin = Math.min(tailMin, popped);
                        tailMax = Math.max(tailMax, popped);
                        outputMin[nOutputs] = tailMin;
                        outputMax[nOutputs] = tailMax;
                        ++nOutputs;
                    }
                    inputMin = Double.POSITIVE_INFINITY;
                    inputMax = Double.NEGATIVE_INFINITY;
                }
                --nOutputs;
            }
            inputStack[nInputs] = value;
            ++nInputs;
            inputMin = Math.min(inputMin, value);
            inputMax = Math.max(inputMax, value);
        }

        int getCapacity() {
            return inputStack.length;
        }

        double getMinimum() {
            return nOutputs == 0 ? inputMin : Math.min(inputMin, outputMin[nOutputs - 1]);
        }

        double getMaximum() {
            return nOutputs == 0 ? inputMax : Math.max(inputMax, outputMax[nOutputs - 1]);
        }
    }

    // -----Matrix utility functions similar to the Matlab build in functions------

    private static RealMatrix log(final RealMatrix m) {
        final double[][] d = new double[m.getRowDimension()][m.getColumnDimension()];
        for (int r = 0; r < m.getRowDimension(); r++) {
            for (int c = 0; c < m.getColumnDimension(); c++) {
                d[r][c] = FastMath.log(m.getEntry(r, c));
            }
        }
        return new Array2DRowRealMatrix(d, false);
    }

    private static RealMatrix sqrt(final RealMatrix m) {
        final double[][] d = new double[m.getRowDimension()][m.getColumnDimension()];
        for (int r = 0; r < m.getRowDimension(); r++) {
            for (int c = 0; c < m.getColumnDimension(); c++) {
                d[r][c] = FastMath.sqrt(m.getEntry(r, c));
            }
        }
        return new Array2DRowRealMatrix(d, false);
    }

    private static RealMatrix square(final RealMatrix m) {
        final double[][] d = new double[m.getRowDimension()][m.getColumnDimension()];
        for (int r = 0; r < m.getRowDimension(); r++) {
            for (int c = 0; c < m.getColumnDimension(); c++) {
                double e = m.getEntry(r, c);
                d[r][c] = e * e;
            }
        }
        return new Array2DRowRealMatrix(d, false);
    }

    private static RealMatrix times(final RealMatrix m, final RealMatrix n) {
        final double[][] d = new double[m.getRowDimension()][m.getColumnDimension()];
        for (int r = 0; r < m.getRowDimension(); r++) {
            for (int c = 0; c < m.getColumnDimension(); c++) {
                d[r][c] = m.getEntry(r, c) * n.getEntry(r, c);
            }
        }
        return new Array2DRowRealMatrix(d, false);
    }

    private static RealMatrix divide(final RealMatrix m, final RealMatrix n) {
        final double[][] d = new double[m.getRowDimension()][m.getColumnDimension()];
        for (int r = 0; r < m.getRowDimension(); r++) {
            for (int c = 0; c < m.getColumnDimension(); c++) {
                d[r][c] = m.getEntry(r, c) / n.getEntry(r, c);
            }
        }
        return new Array2DRowRealMatrix(d, false);
    }

    private static RealMatrix selectColumns(final RealMatrix m, final int[] cols) {
        final double[][] d = new double[m.getRowDimension()][cols.length];
        for (int r = 0; r < m.getRowDimension(); r++) {
            for (int c = 0; c < cols.length; c++) {
                d[r][c] = m.getEntry(r, cols[c]);
            }
        }
        return new Array2DRowRealMatrix(d, false);
    }

    private static RealMatrix upperTriangular(final RealMatrix m, int k) {
        final double[][] d = new double[m.getRowDimension()][m.getColumnDimension()];
        for (int r = 0; r < m.getRowDimension(); r++) {
            for (int c = 0; c < m.getColumnDimension(); c++) {
                d[r][c] = r <= c - k ? m.getEntry(r, c) : 0;
            }
        }
        return new Array2DRowRealMatrix(d, false);
    }

    private static RealMatrix sumRows(final RealMatrix m) {
        final double[][] d = new double[1][m.getColumnDimension()];
        for (int c = 0; c < m.getColumnDimension(); c++) {
            double sum = 0;
            for (int r = 0; r < m.getRowDimension(); r++) {
                sum += m.getEntry(r, c);
            }
            d[0][c] = sum;
        }
        return new Array2DRowRealMatrix(d, false);
    }

    private static RealMatrix diagonal(final RealMatrix m) {
        if (m.getColumnDimension() == 1) {
            final double[][] d = new double[m.getRowDimension()][m.getRowDimension()];
            for (int i = 0; i < m.getRowDimension(); i++) {
                d[i][i] = m.getEntry(i, 0);
            }
            return new Array2DRowRealMatrix(d, false);
        } else {
            final double[][] d = new double[m.getRowDimension()][1];
            for (int i = 0; i < m.getColumnDimension(); i++) {
                d[i][0] = m.getEntry(i, i);
            }
            return new Array2DRowRealMatrix(d, false);
        }
    }

    private static void copyZeroColumn(final RealMatrix m1, RealMatrix m2, int col2) {
        for (int i = 0; i < m1.getRowDimension(); i++) {
            m2.setEntry(i, col2, m1.getEntry(i, 0));
        }
    }

    private static RealMatrix columnOfOnes(int n) {
        final double[][] d = new double[n][1];
        for (int r = 0; r < n; r++) {
            Arrays.fill(d[r], 1);
        }
        return new Array2DRowRealMatrix(d, false);
    }

    private static RealMatrix eye(int n, int m) {
        final double[][] d = new double[n][m];
        for (int r = 0; r < n; r++) {
            if (r < m) {
                d[r][r] = 1;
            }
        }
        return new Array2DRowRealMatrix(d, false);
    }

    private static RealMatrix zeros(int n, int m) {
        return new Array2DRowRealMatrix(n, m);
    }

    private static RealMatrix replicateMatrix(final RealMatrix mat, int n, int m) {
        final int rd = mat.getRowDimension();
        final int cd = mat.getColumnDimension();
        final double[][] d = new double[n * rd][m * cd];
        for (int r = 0; r < n * rd; r++) {
            for (int c = 0; c < m * cd; c++) {
                d[r][c] = mat.getEntry(r % rd, c % cd);
            }
        }
        return new Array2DRowRealMatrix(d, false);
    }

    private static RealMatrix naturals(int size) {
        final double[][] d = new double[size][1];
        double value = 1;
        for (int r = 0; r < size; r++) {
            d[r][0] = value;
            value += 1;
        }
        return new Array2DRowRealMatrix(d, false);
    }

    private static double max(final RealMatrix m) {
        double max = -Double.MAX_VALUE;
        for (int r = 0; r < m.getRowDimension(); r++) {
            for (int c = 0; c < m.getColumnDimension(); c++) {
                double e = m.getEntry(r, c);
                if (max < e) {
                    max = e;
                }
            }
        }
        return max;
    }

    private static double min(final RealMatrix m) {
        double min = Double.MAX_VALUE;
        for (int r = 0; r < m.getRowDimension(); r++) {
            for (int c = 0; c < m.getColumnDimension(); c++) {
                double e = m.getEntry(r, c);
                if (min > e) {
                    min = e;
                }
            }
        }
        return min;
    }

    private static double max(final double[] m) {
        double max = Double.MIN_VALUE;
        for (double v : m) {
            if (max < v) {
                max = v;
            }
        }
        return max;
    }

    private static double min(final double[] m) {
        double min = Double.MAX_VALUE;
        for (double v : m) {
            if (min > v) {
                min = v;
            }
        }
        return min;
    }

    private static int[] inverse(final int[] indices) {
        final int[] inverse = new int[indices.length];
        for (int i = 0; i < indices.length; i++) {
            inverse[indices[i]] = i;
        }
        return inverse;
    }

    private static int[] reverse(final int[] indices) {
        final int[] reverse = new int[indices.length];
        for (int i = 0; i < indices.length; i++) {
            reverse[i] = indices[indices.length - i - 1];
        }
        return reverse;
    }

    private double[] gaussianArray(int size) {
        final double[] result = new double[size];
        for (int i = 0; i < size; i++) {
            result[i] = random.nextGaussian();
        }
        return result;
    }

    private RealMatrix gaussianMatrix(int size, int popSize) {
        final double[][] d = new double[size][popSize];
        for (int r = 0; r < size; r++) {
            for (int c = 0; c < popSize; c++) {
                d[r][c] = random.nextGaussian();
            }
        }
        return new Array2DRowRealMatrix(d, false);
    }
}
