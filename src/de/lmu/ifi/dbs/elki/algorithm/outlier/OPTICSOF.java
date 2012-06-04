package de.lmu.ifi.dbs.elki.algorithm.outlier;
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

import java.util.ArrayList;
import java.util.List;

import de.lmu.ifi.dbs.elki.algorithm.AbstractDistanceBasedAlgorithm;
import de.lmu.ifi.dbs.elki.algorithm.clustering.OPTICS;
import de.lmu.ifi.dbs.elki.data.type.TypeInformation;
import de.lmu.ifi.dbs.elki.data.type.TypeUtil;
import de.lmu.ifi.dbs.elki.database.Database;
import de.lmu.ifi.dbs.elki.database.datastore.DataStoreFactory;
import de.lmu.ifi.dbs.elki.database.datastore.DataStoreUtil;
import de.lmu.ifi.dbs.elki.database.datastore.WritableDataStore;
import de.lmu.ifi.dbs.elki.database.datastore.WritableDoubleDataStore;
import de.lmu.ifi.dbs.elki.database.datastore.WritableIntegerDataStore;
import de.lmu.ifi.dbs.elki.database.ids.DBID;
import de.lmu.ifi.dbs.elki.database.ids.DBIDs;
import de.lmu.ifi.dbs.elki.database.query.DistanceResultPair;
import de.lmu.ifi.dbs.elki.database.query.distance.DistanceQuery;
import de.lmu.ifi.dbs.elki.database.query.knn.KNNQuery;
import de.lmu.ifi.dbs.elki.database.query.knn.KNNResult;
import de.lmu.ifi.dbs.elki.database.query.range.RangeQuery;
import de.lmu.ifi.dbs.elki.database.relation.Relation;
import de.lmu.ifi.dbs.elki.distance.distancefunction.DistanceFunction;
import de.lmu.ifi.dbs.elki.distance.distancevalue.NumberDistance;
import de.lmu.ifi.dbs.elki.logging.Logging;
import de.lmu.ifi.dbs.elki.math.DoubleMinMax;
import de.lmu.ifi.dbs.elki.database.relation.MaterializedRelation;
import de.lmu.ifi.dbs.elki.result.outlier.OutlierResult;
import de.lmu.ifi.dbs.elki.result.outlier.OutlierScoreMeta;
import de.lmu.ifi.dbs.elki.result.outlier.QuotientOutlierScoreMeta;
import de.lmu.ifi.dbs.elki.utilities.documentation.Description;
import de.lmu.ifi.dbs.elki.utilities.documentation.Reference;
import de.lmu.ifi.dbs.elki.utilities.documentation.Title;
import de.lmu.ifi.dbs.elki.utilities.optionhandling.constraints.GreaterConstraint;
import de.lmu.ifi.dbs.elki.utilities.optionhandling.parameterization.Parameterization;
import de.lmu.ifi.dbs.elki.utilities.optionhandling.parameters.IntParameter;

/**
 * OPTICSOF provides the Optics-of algorithm, an algorithm to find Local
 * Outliers in a database.
 * <p>
 * Reference:<br>
 * Markus M. Breunig, Hans-Peter Kriegel, Raymond T. N, Jörg Sander:<br />
 * OPTICS-OF: Identifying Local Outliers<br />
 * In Proc. of the 3rd European Conference on Principles of Knowledge Discovery
 * and Data Mining (PKDD), Prague, Czech Republic
 * 
 * @author Ahmed Hettab
 * 
 * @apiviz.has KNNQuery
 * @apiviz.has RangeQuery
 * @apiviz.has OPTICS
 * 
 * @param <O> DatabaseObject
 */
@Title("OPTICS-OF: Identifying Local Outliers")
@Description("Algorithm to compute density-based local outlier factors in a database based on the neighborhood size parameter 'minpts'")
@Reference(authors = "M. M. Breunig, H.-P. Kriegel, R. Ng, and J. Sander", title = "OPTICS-OF: Identifying Local Outliers", booktitle = "Proc. of the 3rd European Conference on Principles of Knowledge Discovery and Data Mining (PKDD), Prague, Czech Republic", url = "http://springerlink.metapress.com/content/76bx6413gqb4tvta/")
public class OPTICSOF<O, D extends NumberDistance<D, ?>> extends AbstractDistanceBasedAlgorithm<O, D, OutlierResult> implements OutlierAlgorithm {
  /**
   * The logger for this class.
   */
  private static final Logging logger = Logging.getLogger(OPTICSOF.class);

  /**
   * Parameter to specify the threshold MinPts.
   */
  private int minpts;

  /**
   * Constructor with parameters.
   * 
   * @param distanceFunction distance function
   * @param minpts minPts parameter
   */
  public OPTICSOF(DistanceFunction<? super O, D> distanceFunction, int minpts) {
    super(distanceFunction);
    this.minpts = minpts;
  }

  /**
   * Perform OPTICS-based outlier detection.
   * 
   * @param database Database
   * @param relation Relation
   * @return Outlier detection result
   */
  public OutlierResult run(Database database, Relation<O> relation) {
    DistanceQuery<O, D> distQuery = database.getDistanceQuery(relation, getDistanceFunction());
    KNNQuery<O, D> knnQuery = database.getKNNQuery(distQuery, minpts);
    RangeQuery<O, D> rangeQuery = database.getRangeQuery(distQuery);
    DBIDs ids = relation.getDBIDs();

    // FIXME: implicit preprocessor.
    WritableDataStore<KNNResult<D>> nMinPts = DataStoreUtil.makeStorage(ids, DataStoreFactory.HINT_HOT | DataStoreFactory.HINT_TEMP, KNNResult.class);
    WritableDoubleDataStore coreDistance = DataStoreUtil.makeDoubleStorage(ids, DataStoreFactory.HINT_HOT | DataStoreFactory.HINT_TEMP);
    WritableIntegerDataStore minPtsNeighborhoodSize = DataStoreUtil.makeIntegerStorage(ids, DataStoreFactory.HINT_HOT | DataStoreFactory.HINT_TEMP, -1);

    // Pass 1
    // N_minpts(id) and core-distance(id)

    for(DBID id : relation.iterDBIDs()) {
      KNNResult<D> minptsNeighbours = knnQuery.getKNNForDBID(id, minpts);
      D d = minptsNeighbours.getKNNDistance();
      nMinPts.put(id, minptsNeighbours);
      coreDistance.putDouble(id, d.doubleValue());
      minPtsNeighborhoodSize.put(id, rangeQuery.getRangeForDBID(id, d).size());
    }

    // Pass 2
    WritableDataStore<List<Double>> reachDistance = DataStoreUtil.makeStorage(ids, DataStoreFactory.HINT_HOT | DataStoreFactory.HINT_TEMP, List.class);
    WritableDoubleDataStore lrds = DataStoreUtil.makeDoubleStorage(ids, DataStoreFactory.HINT_HOT | DataStoreFactory.HINT_TEMP);
    for(DBID id : relation.iterDBIDs()) {
      List<Double> core = new ArrayList<Double>();
      double lrd = 0;
      for(DistanceResultPair<D> neighPair : nMinPts.get(id)) {
        DBID idN = neighPair.getDBID();
        double coreDist = coreDistance.doubleValue(idN);
        double dist = distQuery.distance(id, idN).doubleValue();
        Double rd = Math.max(coreDist, dist);
        lrd = rd + lrd;
        core.add(rd);
      }
      lrd = minPtsNeighborhoodSize.intValue(id) / lrd;
      reachDistance.put(id, core);
      lrds.putDouble(id, lrd);
    }

    // Pass 3
    DoubleMinMax ofminmax = new DoubleMinMax();
    WritableDoubleDataStore ofs = DataStoreUtil.makeDoubleStorage(ids, DataStoreFactory.HINT_STATIC);
    for(DBID id : relation.iterDBIDs()) {
      double of = 0;
      for(DistanceResultPair<D> pair : nMinPts.get(id)) {
        DBID idN = pair.getDBID();
        double lrd = lrds.doubleValue(id);
        double lrdN = lrds.doubleValue(idN);
        of = of + lrdN / lrd;
      }
      of = of / minPtsNeighborhoodSize.intValue(id);
      ofs.putDouble(id, of);
      // update minimum and maximum
      ofminmax.put(of);
    }
    // Build result representation.
    Relation<Double> scoreResult = new MaterializedRelation<Double>("OPTICS Outlier Scores", "optics-outlier", TypeUtil.DOUBLE, ofs, relation.getDBIDs());
    OutlierScoreMeta scoreMeta = new QuotientOutlierScoreMeta(ofminmax.getMin(), ofminmax.getMax(), 0.0, Double.POSITIVE_INFINITY, 1.0);
    return new OutlierResult(scoreMeta, scoreResult);
  }
  
  @Override
  public TypeInformation[] getInputTypeRestriction() {
    return TypeUtil.array(getDistanceFunction().getInputTypeRestriction());
  }

  @Override
  protected Logging getLogger() {
    return logger;
  }

  /**
   * Parameterization class.
   *
   * @author Erich Schubert
   *
   * @apiviz.exclude
   */
  public static class Parameterizer<O, D extends NumberDistance<D, ?>> extends AbstractDistanceBasedAlgorithm.Parameterizer<O, D> {
    protected int minpts = 0;

    @Override
    protected void makeOptions(Parameterization config) {
      super.makeOptions(config);
      final IntParameter param = new IntParameter(OPTICS.MINPTS_ID, new GreaterConstraint(1));
      if(config.grab(param)) {
        minpts = param.getValue();
      }
    }

    @Override
    protected OPTICSOF<O, D> makeInstance() {
      return new OPTICSOF<O, D>(distanceFunction, minpts);
    }
  }
}