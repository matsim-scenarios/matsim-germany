/* *********************************************************************** *
 * project: org.matsim.*
 * EditRoutesTest.java
 *                                                                         *
 * *********************************************************************** *
 *                                                                         *
 * copyright       : (C) 2019 by the members listed in the COPYING,        *
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

package org.matsim.demand;

import java.io.IOException;
import java.net.MalformedURLException;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.stream.Collectors;

import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVParser;
import org.apache.commons.csv.CSVRecord;
import org.apache.commons.math3.distribution.EnumeratedDistribution;
import org.apache.commons.math3.util.Pair;

import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.core.config.Configurator;
import org.geotools.api.feature.simple.SimpleFeature;
import org.locationtech.jts.geom.*;
import org.matsim.api.core.v01.Coord;
import org.matsim.api.core.v01.Id;
import org.matsim.api.core.v01.TransportMode;
import org.matsim.api.core.v01.population.Activity;
import org.matsim.api.core.v01.population.Leg;
import org.matsim.api.core.v01.population.Person;
import org.matsim.api.core.v01.population.Plan;
import org.matsim.api.core.v01.population.Population;
import org.matsim.api.core.v01.population.PopulationFactory;
import org.matsim.application.options.ShpOptions;
import org.matsim.core.config.ConfigUtils;
import org.matsim.core.population.PopulationUtils;
import org.matsim.core.scenario.ProjectionUtils;
import org.matsim.core.utils.geometry.CoordinateTransformation;
import org.matsim.core.utils.geometry.geotools.MGC;
import org.matsim.core.utils.geometry.transformations.TransformationFactory;
import org.matsim.core.utils.io.IOUtils;

/**
 * @author smueller
 */

public class CreateDemand {

	public static final String DEFAULT_OUTPUT_CRS = "EPSG:25832";
	private static final String NUTS_CRS = "EPSG:31467";
	private static final String RESIDENTIAL_AREAS_CRS = "EPSG:31467";

	private static Map<String, Geometry> regions;
	private static final Map<String, EnumeratedDistribution<Geometry>> landcoverPerZone = new HashMap<>();
	private static Population population;
	private static CoordinateTransformation coordinateTransformation;

	private static final Random random = new Random(4711);
	private static final Random coordinateRandom = new Random(4711);
	private static final TripStartTimeCalculator tripStartTimeCalculator = new DefaultTripStartTimeCalculator(random);

	public static Population create(String outputPopulationFile, String outputCrs, double sample, boolean train, boolean car, boolean airplane, boolean pt, boolean bike, boolean walk) throws MalformedURLException {
		Configurator.setLevel("org.matsim.core.utils.geometry.geotools.MGC", Level.ERROR);
		coordinateTransformation = TransformationFactory.getCoordinateTransformation(RESIDENTIAL_AREAS_CRS, outputCrs);

		//		String shpNUTS3 = "https://svn.vsp.tu-berlin.de/repos/public-svn/matsim/scenarios/countries/de/germany/original_data/shapes/NUTS3/NUTS3_2010_DE.shp";
		String shpNUTS3 = "../public-svn/matsim/scenarios/countries/de/germany/original_data/shapes/NUTS3/NUTS3_2010_DE.shp";
		ShpOptions shpOptionsNUTS = new ShpOptions(shpNUTS3, NUTS_CRS, StandardCharsets.ISO_8859_1);
		ShpOptions.Index indexNUTS = shpOptionsNUTS.createIndex(RESIDENTIAL_AREAS_CRS, "NUTS_ID");
		regions = shpOptionsNUTS.readFeatures().stream().collect(Collectors.toMap(feature -> (String) feature.getAttribute("NUTS_ID"), feature -> (Geometry) feature.getDefaultGeometry()));

		population = PopulationUtils.createPopulation(ConfigUtils.createConfig());
		// Read in landcoverPerZone data to make people stay in populated areas
		// we are using a weighted distribution by area-size, so that small areas receive less inhabitants than more
		// populated ones.
		HashMap<String, List<Pair<Geometry, Double>>> weightedGeometriesPerZones = new HashMap<>();
//		ShpOptions shpOptionsResidentialAreas = new ShpOptions("https://svn.vsp.tu-berlin.de/repos/public-svn/matsim/scenarios/countries/de/germany/original_data/shapes/Landschaftsmodell/sie01_f.shp", "EPSG:31467", StandardCharsets.ISO_8859_1);

		ShpOptions shpOptionsResidentialAreas = new ShpOptions("../public-svn/matsim/scenarios/countries/de/germany/original_data/shapes/Landschaftsmodell/sie01_f.shp", RESIDENTIAL_AREAS_CRS, StandardCharsets.ISO_8859_1);
		for (SimpleFeature residentialArea : shpOptionsResidentialAreas.readFeatures()) {
			Geometry geometry = (Geometry) residentialArea.getDefaultGeometry();
			String zoneNUTS = indexNUTS.query(MGC.point2Coord(geometry.getCentroid()));
			weightedGeometriesPerZones.computeIfAbsent(zoneNUTS, k -> new ArrayList<>());
			weightedGeometriesPerZones.get(zoneNUTS).add(new Pair<>(geometry, geometry.getArea()));
		}

		weightedGeometriesPerZones.forEach((thisZone, geometryPairs) -> landcoverPerZone.put(thisZone, new EnumeratedDistribution<>(geometryPairs)));
		String demandInput = "../shared-svn/studies/countries/de/prognose_2030/PVMatrix_BVWP15_A2010/SM_PVMatrix_BVWP15_A2010.csv";
		// read the bvwp csv file
		try (CSVParser parser = CSVFormat.Builder.create(CSVFormat.DEFAULT).setDelimiter(';').setHeader().setSkipHeaderRecord(true).build().parse(IOUtils.getBufferedReader(demandInput))) {

			// this will iterate over every line in the csv except the first one which contains the column headers
			for (CSVRecord record : parser) {

				String originZone = record.get("Quelle_Nuts3");
				String destinationZone = record.get("Ziel_Nuts3");
				List<String> csvColumns = new ArrayList<>();

				if (train) {
					csvColumns.add("Bahn_Fz1");
					csvColumns.add("Bahn_Fz2");
					csvColumns.add("Bahn_Fz3");
					csvColumns.add("Bahn_Fz4");
					csvColumns.add("Bahn_Fz5");
					csvColumns.add("Bahn_Fz6");
				}

				if (car) {
					csvColumns.add("MIV_Fz1");
					csvColumns.add("MIV_Fz2");
					csvColumns.add("MIV_Fz3");
					csvColumns.add("MIV_Fz4");
					csvColumns.add("MIV_Fz5");
					csvColumns.add("MIV_Fz6");
				}

				if (airplane) {
					csvColumns.add("Luft_Fz1");
					csvColumns.add("Luft_Fz2");
					csvColumns.add("Luft_Fz3");
					csvColumns.add("Luft_Fz4");
					csvColumns.add("Luft_Fz5");
					csvColumns.add("Luft_Fz6");
				}

				if (pt) {
					csvColumns.add("OESPV_Fz1");
					csvColumns.add("OESPV_Fz2");
					csvColumns.add("OESPV_Fz3");
					csvColumns.add("OESPV_Fz4");
					csvColumns.add("OESPV_Fz5");
					csvColumns.add("OESPV_Fz6");
				}


				if (bike) {
					csvColumns.add("Rad_Fz1");
					csvColumns.add("Rad_Fz2");
					csvColumns.add("Rad_Fz3");
					csvColumns.add("Rad_Fz4");
					csvColumns.add("Rad_Fz5");
					csvColumns.add("Rad_Fz6");
				}

				if (walk) {
					csvColumns.add("Fuss_Fz1");
					csvColumns.add("Fuss_Fz2");
					csvColumns.add("Fuss_Fz3");
					csvColumns.add("Fuss_Fz4");
					csvColumns.add("Fuss_Fz5");
					csvColumns.add("Fuss_Fz6");
				}

				for (String csvColumn : csvColumns) {
					String mode = null;
					String nextActType = null;

					int noOfAgentsPerDay = (int) Math.round(Integer.parseInt((record.get(csvColumn))) / 365.0);

//                    double rest = noOfAgentsDouble - noOfAgentsPerDay;
//
//                    if (rest > random.nextDouble()) {
//                        noOfAgentsPerDay++;
//                    }

					String[] splitColumn = csvColumn.split("_");

					mode = switch (splitColumn[0]) {
						case "Bahn" ->
//						mode = TransportMode.train;
							"longDistancePt";
						case "MIV" -> TransportMode.car;
						case "Luft" ->
//						mode = TransportMode.airplane;
							"longDistancePt";
						case "OESPV" ->
//						mode = TransportMode.pt;
							"longDistancePt";
						case "Rad" -> TransportMode.bike;
						case "Fuss" -> TransportMode.walk;
						default -> throw new IllegalStateException("Unexpected value in column 0: " + splitColumn[0]);
					};

					nextActType = switch (splitColumn[1]) {
						case "Fz1" -> "work";
						case "Fz2" -> "education";
						case "Fz3" -> "shop";
						case "Fz4" -> "business";
						case "Fz5" -> "holiday";
						case "Fz6" -> "other";
						default -> throw new IllegalStateException("Unexpected value in column 1: " + splitColumn[1]);
					};

					if (!originZone.equals(destinationZone)) {
//					agents travelling from Berlin to Munich + Umland
//					if(originZone.equals("DE300") && destinationZone.equals("DEA23")) {
//					if ((originZone.equals("DE300") || originZone.equals("DE40A") || originZone.equals("DE405") || originZone.equals("DE409") || originZone.equals("DE40C") || originZone.equals("DE406") || originZone.equals("DE40H") || originZone.equals("DE40E") || originZone.equals("DE404") || originZone.equals("DE408"))  && (destinationZone.equals("DEA23") || destinationZone.equals("DEA27") || destinationZone.equals("DEA1D") || destinationZone.equals("DEA1C") || destinationZone.equals("DEA24") || destinationZone.equals("DEA2B") || destinationZone.equals("DEA2C"))) {

//					if ((originZone.equals("DE300") || originZone.equals("DE40A") || originZone.equals("DE405") || originZone.equals("DE409") || originZone.equals("DE40C") || originZone.equals("DE406") || originZone.equals("DE40H") || originZone.equals("DE40E") || originZone.equals("DE404") || originZone.equals("DE408"))  && (destinationZone.equals("DE212") || destinationZone.equals("DE21H") || destinationZone.equals("DE21L") || destinationZone.equals("DE21C") || destinationZone.equals("DE217"))) {
						createPersons(sample, originZone, destinationZone, noOfAgentsPerDay, mode, nextActType);
					}

				}

			}
		} catch (IOException e) {
			e.printStackTrace();
		}
		ProjectionUtils.putCRS(population, outputCrs);
		PopulationUtils.writePopulation(population, outputPopulationFile);
		return population;
	}


	private static void createPersons(double sample, String originZone, String destinationZone, int noOfAgentsPerDay, String mode, String nextActType) {


//			log.warn("Creating persons: " + oGeometry + " --- " + dGeometry + " --- " + noOfAgents + " --- " + mode + " --- " + nextActType);
		if (regions.containsKey(originZone) && regions.containsKey(destinationZone)) {
			for (int ii = 0; ii < noOfAgentsPerDay; ii++) {
//				sample size
				if (random.nextDouble() < sample)
					createPerson(originZone, destinationZone, mode, nextActType);
			}
		}

	}

	private static void createPerson(String originZone, String destinationZone, String mode, String nextActType) {

		PopulationFactory populationFactory = population.getFactory();
		int index = population.getPersons().size();
		Id<Person> id = Id.createPersonId(originZone + "---" + destinationZone + "---" + mode + "---" + index);
		Person person = populationFactory.createPerson(id);
		PopulationUtils.putSubpopulation(person, "personGermanyModel");
		population.addPerson(person);

		Plan plan = populationFactory.createPlan();
		person.addPlan(plan);

		Coord originCoord = getCoordInGeometry(originZone);
		Activity originAct = populationFactory.createActivityFromCoord("origin", originCoord);
//		Todo: Tagesgang
		int tripStartTime = createTripStartTime(nextActType);
		originAct.setEndTime(tripStartTime);
		plan.addActivity(originAct);

		Leg leg = populationFactory.createLeg(mode);
		plan.addLeg(leg);

		Coord destinationCoord = getCoordInGeometry(destinationZone);
		Activity destinationAct = populationFactory.createActivityFromCoord(nextActType, destinationCoord);
//		ToDo: Tagesgang
//		destinationAct.setStartTime(12. * 3600);
		plan.addActivity(destinationAct);
	}

	private static int createTripStartTime(String nextActType) {
		return tripStartTimeCalculator.createTripStartTime(nextActType);
	}

	private static Coord getCoordInGeometry(String zone) {

		EnumeratedDistribution<Geometry> landcoverDistribution = landcoverPerZone.get(zone);
		if (landcoverDistribution == null) {
			throw new IllegalArgumentException("No residential-area geometry found for NUTS-3 zone " + zone);
		}
		Geometry selectedLandcover = landcoverDistribution.sample();

		Point point = getRandomPoint(selectedLandcover);
		return coordinateTransformation.transform(MGC.point2Coord(point));
	}

	/**
	 * Samples a uniformly distributed point within the given geometry using rejection sampling on its bounding box.
	 * The returned point uses the geometry's coordinate system. If no point can be found after 10,000 attempts, the
	 * geometry's interior point is returned as a fallback.
	 *
	 * @param geometry geometry in which the point is sampled
	 * @return a point covered by the geometry
	 */
	private static Point getRandomPoint(Geometry geometry) {
		Envelope envelope = geometry.getEnvelopeInternal();
		for (int attempt = 0; attempt < 10_000; attempt++) {
			double x = envelope.getMinX() + envelope.getWidth() * coordinateRandom.nextDouble();
			double y = envelope.getMinY() + envelope.getHeight() * coordinateRandom.nextDouble();
			Point point = MGC.xy2Point(x, y);
			if (geometry.covers(point)) {
				return point;
			}
		}
		return geometry.getInteriorPoint();
	}

	/**
	 * Calculates a trip start time based on the destination activity type.
	 */
	public interface TripStartTimeCalculator {
		int createTripStartTime(String nextActType);
	}
}
