package org.locationtech.geomesa.spark.hbase

import org.apache.commons.io.IOUtils
import org.apache.hadoop.conf.Configuration
import org.apache.hadoop.io.Text
import org.apache.spark.SparkContext
import org.apache.spark.rdd.RDD
import org.geotools.data.{DataStoreFinder, Query, Transaction}
import org.locationtech.geomesa.hbase.data.{EmptyPlan, HBaseDataStore, HBaseDataStoreFactory}
import org.locationtech.geomesa.spark.SpatialRDDProvider
import org.locationtech.geomesa.utils.geotools.FeatureUtils
import org.opengis.feature.simple.SimpleFeature

/**
  * Created by afox on 2/8/17.
  */
class HBaseSpatialRDDProvider extends SpatialRDDProvider {
  import org.locationtech.geomesa.spark.CaseInsensitiveMapFix._

  override def canProcess(params: java.util.Map[String, java.io.Serializable]): Boolean =
    HBaseDataStoreFactory.canProcess(params)

  def rdd(conf: Configuration,
          sc: SparkContext,
          dsParams: Map[String, String],
          query: Query): RDD[SimpleFeature] = {
    val ds = DataStoreFinder.getDataStore(dsParams).asInstanceOf[HBaseDataStore]

    try {
      // get the query plan to set up the iterators, ranges, etc
      lazy val sft = ds.getSchema(query.getTypeName)
      lazy val qp = ds.getQueryPlan(query)

      if (ds == null || sft == null || qp.isInstanceOf[EmptyPlan]) {
        sc.emptyRDD[SimpleFeature]
      } else {
        sc.newAPIHadoopRDD(conf, classOf[GeoMesaHBaseInputFormat], classOf[Text], classOf[SimpleFeature]).map(U => U._2)
      }
    } finally {
      if (ds != null) {
        ds.dispose()
      }
    }
  }

  /**
    * Writes this RDD to a GeoMesa table.
    * The type must exist in the data store, and all of the features in the RDD must be of this type.
    *
    * @param rdd
    * @param writeDataStoreParams
    * @param writeTypeName
    */
  def save(rdd: RDD[SimpleFeature], writeDataStoreParams: Map[String, String], writeTypeName: String): Unit = {
    val ds = DataStoreFinder.getDataStore(writeDataStoreParams).asInstanceOf[HBaseDataStore]
    try {
      require(ds.getSchema(writeTypeName) != null,
        "Feature type must exist before calling save.  Call createSchema on the DataStore first.")
    } finally {
      ds.dispose()
    }

    rdd.foreachPartition { iter =>
      val ds = DataStoreFinder.getDataStore(writeDataStoreParams).asInstanceOf[HBaseDataStore]
      val featureWriter = ds.getFeatureWriterAppend(writeTypeName, Transaction.AUTO_COMMIT)
      try {
        iter.foreach { rawFeature =>
          FeatureUtils.copyToWriter(featureWriter, rawFeature, overrideFid = true)
          featureWriter.write()
        }
      } finally {
        IOUtils.closeQuietly(featureWriter)
        ds.dispose()
      }
    }
  }

}
