/***********************************************************************
* Copyright (c) 2013-2016 Commonwealth Computer Research, Inc.
* All rights reserved. This program and the accompanying materials
* are made available under the terms of the Apache License, Version 2.0
* which accompanies this distribution and is available at
* http://www.opensource.org/licenses/apache2.0.php.
*************************************************************************/

package org.locationtech.geomesa.tools.export.formats

import java.io.Writer
import java.util.Date

import com.typesafe.scalalogging.LazyLogging
import com.vividsolutions.jts.geom.Geometry
import org.apache.commons.csv.{CSVFormat, QuoteMode}
import org.geotools.data.simple.SimpleFeatureCollection
import org.locationtech.geomesa.tools.utils.DataFormats
import org.locationtech.geomesa.tools.utils.DataFormats._
import org.locationtech.geomesa.utils.geotools.SimpleFeatureTypes
import org.locationtech.geomesa.utils.text.WKTUtils

class DelimitedExporter(writer: Writer, format: DataFormat, withHeader: Boolean = true)
    extends FeatureExporter with LazyLogging {

  import scala.collection.JavaConversions._

  private val printer = format match {
    case DataFormats.Csv => CSVFormat.DEFAULT.withQuoteMode(QuoteMode.MINIMAL).print(writer)
    case DataFormats.Tsv => CSVFormat.TDF.withQuoteMode(QuoteMode.MINIMAL).print(writer)
  }

  override def export(features: SimpleFeatureCollection): Option[Long] = {
    import org.locationtech.geomesa.utils.geotools.Conversions.toRichSimpleFeatureIterator

    val sft = features.getSchema

    val names = sft.getAttributeDescriptors.map(_.getLocalName)
    val indices = names.map(sft.indexOf)

    val headers = indices.map(sft.getDescriptor).map(SimpleFeatureTypes.encodeDescriptor(sft, _))

    // write out a header line
    if (withHeader) {
      printer.print("id")
      printer.printRecord(headers: _*)
    }

    var count = 0L
    features.features.foreach { sf =>
      printer.print(sf.getID)
      printer.printRecord(sf.getAttributes.map(stringify): _*)
      count += 1
      if (count % 10000 == 0) {
        logger.debug(s"wrote $count features")
      }
    }
    logger.info(s"Exported $count features")
    Some(count)
  }

  def stringify(o: Any): String = {
    import org.locationtech.geomesa.utils.geotools.GeoToolsDateFormat
    o match {
      case null                   => ""
      case g: Geometry            => WKTUtils.write(g)
      case d: Date                => GeoToolsDateFormat.print(d.getTime)
      case l: java.util.List[_]   => l.map(stringify).mkString(",")
      case m: java.util.Map[_, _] => m.map { case (k, v) => s"${stringify(k)}->${stringify(v)}"}.mkString(",")
      case _                      => o.toString
    }
  }

  override def flush(): Unit = printer.flush()

  override def close(): Unit = {
    printer.flush()
    printer.close()
  }
}


