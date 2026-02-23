/* *********************************************************************** *
 * project: org.matsim.*
 * ZoneCentroidLocationCalculator.java
 *                                                                         *
 * *********************************************************************** *
 *                                                                         *
 * copyright       : (C) 2026 by the members listed in the COPYING,        *
 *                   LICENSE and WARRANTY file.                            *
 * email           : info at matsim dot org                                *
 *                                                                         *
 * *********************************************************************** *
 *                                                                         *
 *   This program is free software; you can redistribute it and/or modify  *
 *   it under the terms of the GNU General Public License as published by  *
 *   the Free Software Foundation; either version 2 of the License, or     *
 *   (at your option) any later version.                                   *
 *   See also COPYING, LICENSE and WARRANTY file                           *
 *                                                                         *
 * *********************************************************************** */

package org.matsim.prepare.longDistanceFreightGER;

import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVParser;
import org.apache.commons.csv.CSVRecord;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.geotools.api.feature.simple.SimpleFeature;
import org.locationtech.jts.geom.Geometry;
import org.locationtech.jts.geom.Point;
import org.matsim.api.core.v01.Coord;
import org.matsim.application.options.ShpOptions;
import org.matsim.core.gbl.Gbl;
import org.matsim.core.utils.geometry.CoordinateTransformation;
import org.matsim.core.utils.geometry.geotools.MGC;
import org.matsim.core.utils.geometry.transformations.GeotoolsTransformation;
import org.matsim.core.utils.io.IOUtils;
import org.matsim.core.utils.misc.Counter;

import java.io.BufferedReader;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.*;

/**
 * Determine the start and end locations of the freight trips based on the NUTS3 shape file. KN says demand generation should not rely on a
 * specific network, so only put coord.
 * Therefore, this variant of DefaultLocationCalculator simply selects the zone centroid.
 */
class ZoneCentroidLocationCalculator implements FreightAgentGenerator.LocationCalculator {
	private final static Logger logger = LogManager.getLogger(ZoneCentroidLocationCalculator.class);
	// This CRS is used as a backup for the longitude, latitude coordinates for the international locations where we don't have a network.
	private static final String CRS_BACKUP_LONG_LAT = "EPSG:4326";
	private final Random rnd = new Random(5678);
	private final String crsMatsim;
	private final Map<String, Coord> zone2CentroidMapping = new HashMap<>();
	private final ShpOptions shp;
	private static final String lookUpTablePath = "https://svn.vsp.tu-berlin.de/repos/public-svn/matsim/" +
		"scenarios/countries/de/german-wide-freight/v2/processed-data/complete-lookup-table.csv";

	public ZoneCentroidLocationCalculator(String shpFilePath, String crsMatsim) throws IOException {

//		this.shp = new ShpOptions(shpFilePath, "EPSG:4326", StandardCharsets.ISO_8859_1);
//		this.shp = new ShpOptions(shpFilePath, "EPSG:25832", StandardCharsets.ISO_8859_1);
		// would be far better to get the CRS directly out of the shapefile.
		// Is actually possible, as follows:
		this.shp = new ShpOptions(shpFilePath, null, StandardCharsets.ISO_8859_1);

		// Reading shapefile from URL may not work properly, therefore, users may need to download the shape file to the local directory
		this.crsMatsim = crsMatsim;
		prepareMapping();
	}

	private void prepareMapping() throws IOException {
		logger.info("Reading NUTS shape files...");
		Set<String> relevantNutsIds = new HashSet<>();
		try (BufferedReader reader = IOUtils.getBufferedReader(IOUtils.resolveFileOrResource(lookUpTablePath), StandardCharsets.UTF_8)) {
			CSVParser parser = CSVFormat.Builder.create(CSVFormat.DEFAULT).setDelimiter(';').setHeader()
				.setSkipHeaderRecord(true).get().parse(reader);
			for (CSVRecord record : parser) {
				if (!record.get(3).isEmpty()) {
					relevantNutsIds.add(record.get(3));
				}
			}
		}

		ShpOptions.Index shpIndex = shp.createIndex(shp.getShapeCrs(), "NUTS_ID", ft -> relevantNutsIds.contains(Objects.toString(ft.getAttribute("NUTS_ID"))));
		// This creates an index.  One will be able to put in coordinates, and get out NUTS_IDs.  Those NUTS_IDs can be filtered down to those which
		// are in relevantNutsIDs.  The query coordinate system is given.  The index will automatically transform it to the coordinate system of the
		// shapefile, and then do the query.  What comes back is an attribute value, which has nothing to do with a coordinate system.

		Map<String, Coord> nutsToCentroidMapping = new HashMap<>();

		CoordinateTransformation coordTransformShp2Network = shp.createInverseTransformation(crsMatsim);

		for (SimpleFeature feature : shpIndex.getAllFeatures()) {
			Point centroid = ((Geometry) feature.getDefaultGeometry()).getCentroid();
			Coord centroidMatsim = coordTransformShp2Network.transform(MGC.point2Coord(centroid));
			nutsToCentroidMapping.put((String) feature.getAttribute("NUTS_ID"), centroidMatsim);
		}

		logger.info("Network and shapefile processing complete!");

		logger.info("Computing mapping between Verkehrszelle and departure location...");
		try (BufferedReader reader = IOUtils.getBufferedReader(IOUtils.resolveFileOrResource(lookUpTablePath), StandardCharsets.UTF_8)) {
			CSVParser parser = CSVFormat.Builder.create(CSVFormat.DEFAULT).setDelimiter(';').setHeader()
				.setSkipHeaderRecord(true).get().parse(reader);
			CoordinateTransformation ct = new GeotoolsTransformation(CRS_BACKUP_LONG_LAT, crsMatsim);

			Counter recordCounter = new Counter( "record: " );
			for (CSVRecord record : parser) {
				recordCounter.incCounter();
				String verkehrszelle = record.get(0);
				String nuts2021 = record.get(3);

				// (a) Under "normal" circumstances, we just memorize the mapping:
				if (!nuts2021.isEmpty() && nutsToCentroidMapping.get(nuts2021) != null) {
					zone2CentroidMapping.put(verkehrszelle, nutsToCentroidMapping.get(nuts2021));
					continue;
				}

				logger.info(""); // empty line

				// (b) For some international locations (i,e, not neighboring countries of Germany), no suitable NUTS code is present.
				// In that case, we use the backup coord in the look-up table (manually specified, in longitude, latitude format, i.e., EPSG:4326)

				double xx = Double.parseDouble( record.get( 5 ) );
				double yy = Double.parseDouble( record.get( 6 ) );

				if ( xx < -6 ) {
					logger.warn( "moving a record from too far away since sometimes the coordinate conversion fails.") ;
					xx = -6;
				}
				if ( xx > 20 ) {
					logger.warn( "moving a record from too far away since sometimes the coordinate conversion fails.") ;
					xx = 20;
				}
				// (Alternatively, one could catch the projection exception.  However, since the production code behaves differently from the test, I
				// want to have both of them skip the same records, even if the coordinate transform does not fail with the production code.  kai,
				// aug'25)
				// (As another alternative, one could first move the values closer to the valid regime, then do the transform, than move them back
				// to their original area.  kai, aug'25)

				Coord backupCoord = new Coord( xx, yy );
				logger.info( "backupCoord={}", backupCoord );


				Coord transformedCoord = ct.transform(backupCoord);
				logger.info( "transformedCoord={}", transformedCoord);

				Gbl.assertNotNull( transformedCoord ); // the "assert" keyword, used in the line before, has a tendency to not work.  kai, aug'25
				zone2CentroidMapping.put(verkehrszelle, transformedCoord);
			}
		}
		logger.info("Location generator is successfully created!");
	}

	@Override
	public Coord getCoord(String verkehrszelle) {
		return zone2CentroidMapping.get(verkehrszelle);
	}
}
