package experimentalcode.shared.parallelcoord;

/*
 This file is part of ELKI:
 Environment for Developing KDD-Applications Supported by Index-Structures

 Copyright (C) 2012
 Ludwig-Maximilians-Universität München
 Lehr- und Forschungseinheit für Datenbanksysteme
 ELKI Development Team

 This program is free software: you can redistribute it and/or modify
 it under the terms of the GNU Affero General Public License as published by
 the Free Software Foundation, either version 3 of the License, or
 (at your option) any later version.

 This program is distributed in the hope that it will be useful,
 but WITHOUT ANY WARRANTY; without even the implied warranty of
 MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 GNU Affero General Public License for more details.

 You should have received a copy of the GNU Affero General Public License
 along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */

import de.lmu.ifi.dbs.elki.data.NumberVector;
import de.lmu.ifi.dbs.elki.database.ids.DBIDIter;
import de.lmu.ifi.dbs.elki.database.ids.DBIDs;
import de.lmu.ifi.dbs.elki.database.relation.Relation;
import de.lmu.ifi.dbs.elki.database.relation.RelationUtil;
import de.lmu.ifi.dbs.elki.utilities.DatabaseUtil;
import de.lmu.ifi.dbs.elki.utilities.pairs.Pair;

/**
 * Arrange dimensions based on the entropy of the slope spectrum. In contrast to
 * {@link SlopeDimensionSimilarity}, we also take the option of inverting an
 * axis into account.
 * 
 * TODO: shouldn't this be normalized by the single-dimension entropies or so?
 * 
 * @author Erich Schubert
 * @author Robert Rödler
 */
public class SlopeInversionDimensionSimilarity implements DimensionSimilarity<NumberVector<?>> {
  /**
   * Full precision.
   */
  private final static int PRECISION = 40;

  /**
   * Precision for entropy normalization.
   */
  private final static double LOG_PRECISION = Math.log(PRECISION);

  /**
   * Scaling factor.
   */
  private final static double RESCALE = PRECISION * .5;

  @Override
  public double[][] computeDimensionSimilarites(Relation<? extends NumberVector<?>> relation, DBIDs subset) {
    final int dim = RelationUtil.dimensionality(relation);
    final int size = subset.size();

    // Collect angular histograms.
    // Note, we only fill half of the matrix
    int[][][] angles = new int[dim][dim][PRECISION];
    int[][][] angleI = new int[dim][dim][PRECISION];

    // FIXME: Get/keep these statistics in the relation, or compute for the
    // sample only.
    double[] off = new double[dim], scale = new double[dim];
    {
      Pair<? extends NumberVector<?>, ? extends NumberVector<?>> mm = DatabaseUtil.computeMinMax(relation);
      NumberVector<?> min = mm.first;
      NumberVector<?> max = mm.second;
      for (int d = 0; d < dim; d++) {
        off[d] = min.doubleValue(d);
        final double m = max.doubleValue(d);
        scale[d] = (m > off[d]) ? 1. / (m - off[d]) : 1;
      }
    }

    // Scratch buffer
    double[] vec = new double[dim];
    for (DBIDIter id = subset.iter(); id.valid(); id.advance()) {
      final NumberVector<?> obj = relation.get(id);
      // Map values to 0..1
      for (int d = 0; d < dim; d++) {
        vec[d] = (obj.doubleValue(d) - off[d]) * scale[d];
      }
      for (int i = 0; i < dim - 1; i++) {
        for (int j = i + 1; j < dim; j++) {
          {
            // This will be on a scale of 0 .. 2:
            final double delta = vec[j] - vec[i] + 1;
            int div = (int) Math.round(delta * RESCALE);
            // TODO: do we really need this check?
            div = (div < 0) ? 0 : (div >= PRECISION) ? PRECISION - 1 : div;
            angles[i][j][div] += 1;
          }
          {
            // This will be on a scale of 0 .. 2:
            final double delta = vec[j] + vec[i];
            int div = (int) Math.round(delta * RESCALE);
            // TODO: do we really need this check?
            div = (div < 0) ? 0 : (div >= PRECISION) ? PRECISION - 1 : div;
            angleI[i][j][div] += 1;
          }
        }
      }
    }

    // Compute entropy in each combination:
    double[][] angmat = new double[dim][dim];
    for (int i = 0; i < dim - 1; i++) {
      for (int j = i + 1; j < dim; j++) {
        double entropy = 0., entropyI = 0;
        {
          int[] as = angles[i][j];
          for (int l = 0; l < PRECISION; l++) {
            if (as[l] > 0) {
              final double p = as[l] / (double) size;
              entropy += p * Math.log(p);
            }
          }
        }
        {
          int[] as = angleI[i][j];
          for (int l = 0; l < PRECISION; l++) {
            if (as[l] > 0) {
              final double p = as[l] / (double) size;
              entropyI += p * Math.log(p);
            }
          }
        }
        if (entropy >= entropyI) {
          entropy = 1 + entropy / LOG_PRECISION;
          angmat[i][j] = entropy;
          angmat[j][i] = entropy;
        } else {
          entropyI = 1 + entropyI / LOG_PRECISION;
          // Negative sign to indicate the axes might be inversely related
          angmat[i][j] = -entropyI;
          angmat[j][i] = -entropyI;
        }
      }
    }
    return angmat;
  }
}
