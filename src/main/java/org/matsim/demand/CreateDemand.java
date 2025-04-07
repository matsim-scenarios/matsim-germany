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
import org.matsim.api.core.v01.population.PopulationWriter;
import org.matsim.application.options.ShpOptions;
import org.matsim.core.config.ConfigUtils;
import org.matsim.core.population.PopulationUtils;
import org.matsim.core.utils.geometry.CoordinateTransformation;
import org.matsim.core.utils.geometry.geotools.MGC;
import org.matsim.core.utils.geometry.transformations.TransformationFactory;
import org.matsim.core.utils.io.IOUtils;

/**
* @author smueller
*/

public class CreateDemand {

	
	private static Map<String, Geometry> regions;
	private static Map <String, EnumeratedDistribution<Geometry>> landcoverPerZone = new HashMap<>();
	private static Population population;
	private static final GeometryFactory geometryFactory = new GeometryFactory();
	
	private static final Random random = new Random(100);
//	private static final Random random = MatsimRandom.getLocalInstance();
	
	
	public static Population create(String outputPopulationFile, double sample, boolean train, boolean car, boolean airplane, boolean pt, boolean bike, boolean walk) throws MalformedURLException {
		Configurator.setLevel("org.matsim.core.utils.geometry.geotools.MGC", Level.ERROR);

		//		String shpNUTS3 = "https://svn.vsp.tu-berlin.de/repos/public-svn/matsim/scenarios/countries/de/germany/original_data/shapes/NUTS3/NUTS3_2010_DE.shp";
		String shpNUTS3 = "../public-svn/matsim/scenarios/countries/de/germany/original_data/shapes/NUTS3/NUTS3_2010_DE.shp";
		ShpOptions shpOptionsNUTS = new ShpOptions(shpNUTS3,"EPSG:4326", StandardCharsets.ISO_8859_1);
		ShpOptions.Index indexNUTS = shpOptionsNUTS.createIndex("NUTS_ID");
		regions = shpOptionsNUTS.readFeatures().stream().collect(Collectors.toMap(feature -> (String)feature.getAttribute("NUTS_ID"), feature -> (Geometry) feature.getDefaultGeometry()));
		
		population = PopulationUtils.createPopulation(ConfigUtils.createConfig());
		// Read in landcoverPerZone data to make people stay in populated areas
		// we are using a weighted distribution by area-size, so that small areas receive less inhabitants than more
		// populated ones.
		HashMap<String, List<Pair<Geometry, Double>>> weightedGeometriesPerZones = new HashMap<>();
//		ShpOptions shpOptionsResidentialAreas = new ShpOptions("https://svn.vsp.tu-berlin.de/repos/public-svn/matsim/scenarios/countries/de/germany/original_data/shapes/Landschaftsmodell/sie01_f.shp", "EPSG:31467", StandardCharsets.ISO_8859_1);

		ShpOptions shpOptionsResidentialAreas = new ShpOptions("../public-svn/matsim/scenarios/countries/de/germany/original_data/shapes/Landschaftsmodell/sie01_f.shp", "EPSG:31467", StandardCharsets.ISO_8859_1);
		for (SimpleFeature residentialArea : shpOptionsResidentialAreas.readFeatures()) {
			Geometry geometry = (Geometry) residentialArea.getDefaultGeometry();
			String zoneNUTS = indexNUTS.query(MGC.point2Coord(geometry.getCentroid()));
			weightedGeometriesPerZones.computeIfAbsent(zoneNUTS, k -> new ArrayList<>());
			weightedGeometriesPerZones.get(zoneNUTS).add(new Pair<>(geometry, geometry.getArea()));
		}
		
		weightedGeometriesPerZones.forEach((thisZone,  geometryPairs)-> landcoverPerZone.put(thisZone,new EnumeratedDistribution<>(geometryPairs))); ;
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

				if(pt) {
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
		
		PopulationWriter populationWriter = new PopulationWriter(population);
		populationWriter.write(outputPopulationFile);
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
//		trip start times are set dependent on activity type
//		source for values is: MID 2017 Tabelle A W7 Startzeit
//		MID doesn't contain values for holiday, so the general values for all trips are used
//		other is interpreted as "Freizeit"
		int tripStartTime = -1;
		double localRandom = random.nextDouble();
		
		switch(nextActType) {
		case "work":
			if(localRandom < 0.04) {
				tripStartTime = (int) Math.round(-0.5 + 5 * 3600 * random.nextDouble());
			}
			if(localRandom >= 0.04 && localRandom < 0.35 ) {
				tripStartTime = 5 * 3600 + (int)Math.round(-0.5 + 3 * 3600 * random.nextDouble());
			}
			if(localRandom >= 0.35 && localRandom < 0.45 ) {
				tripStartTime = 8 * 3600 + (int)Math.round(-0.5 + 2 * 3600 * random.nextDouble());
			}
			if(localRandom >= 0.45 && localRandom < 0.54 ) {
				tripStartTime = 10 * 3600 + (int)Math.round(-0.5 + 3 * 3600 * random.nextDouble());
			}
			if(localRandom >= 0.54 && localRandom < 0.72 ) {
				tripStartTime = 13 * 3600 + (int)Math.round(-0.5 + 3 * 3600 * random.nextDouble());
			}
			if(localRandom >= 0.72 && localRandom < 0.94 ) {
				tripStartTime = 16 * 3600 + (int)Math.round(-0.5 + 3 * 3600 * random.nextDouble());
			}
			if(localRandom >= 0.94 ) {
				tripStartTime = 19 * 3600 + (int)Math.round(-0.5 + 3 * 3600 * random.nextDouble());
			}
			break;
			
		case "education":
			if(localRandom < 0.0 ) {
				tripStartTime = (int) Math.round(-0.5 + 5 * 3600 * random.nextDouble());
			}
			if(localRandom >= 0.0 && localRandom < 0.36 ) {
				tripStartTime = 5 * 3600 + (int)Math.round(-0.5 + 3 * 3600 * random.nextDouble());
			}
			if(localRandom >= 0.36 && localRandom < 0.47 ) {
				tripStartTime = 8 * 3600 + (int)Math.round(-0.5 + 2 * 3600 * random.nextDouble());
			}
			if(localRandom >= 0.47 && localRandom < 0.58 ) {
				tripStartTime = 10 * 3600 + (int)Math.round(-0.5 + 3 * 3600 * random.nextDouble());
			}
			if(localRandom >= 0.58 && localRandom < 0.85 ) {
				tripStartTime = 13 * 3600 + (int)Math.round(-0.5 + 3 * 3600 * random.nextDouble());
			}
			if(localRandom >= 0.85 && localRandom < 0.97 ) {
				tripStartTime = 16 * 3600 + (int)Math.round(-0.5 + 3 * 3600 * random.nextDouble());
			}
			if(localRandom >= 0.97 ) {
				tripStartTime = 19 * 3600 + (int)Math.round(-0.5 + 3 * 3600 * random.nextDouble());
			}
			break;
			
		case "shop":
			if(localRandom < 0.0 ) {
				tripStartTime = (int) Math.round(-0.5 + 5 * 3600 * random.nextDouble());
			}
			if(localRandom >= 0.0 && localRandom < 0.03 ) {
				tripStartTime = 5 * 3600 + (int)Math.round(-0.5 + 3 * 3600 * random.nextDouble());
			}
			if(localRandom >= 0.03 && localRandom < 0.21 ) {
				tripStartTime = 8 * 3600 + (int)Math.round(-0.5 + 2 * 3600 * random.nextDouble());
			}
			if(localRandom >= 0.21 && localRandom < 0.53 ) {
				tripStartTime = 10 * 3600 + (int)Math.round(-0.5 + 3 * 3600 * random.nextDouble());
			}
			if(localRandom >= 0.53 && localRandom < 0.74 ) {
				tripStartTime = 13 * 3600 + (int)Math.round(-0.5 + 3 * 3600 * random.nextDouble());
			}
			if(localRandom >= 0.74 && localRandom < 0.95 ) {
				tripStartTime = 16 * 3600 + (int)Math.round(-0.5 + 3 * 3600 * random.nextDouble());
			}
			if(localRandom >= 0.95 ) {
				tripStartTime = 19 * 3600 + (int)Math.round(-0.5 + 3 * 3600 * random.nextDouble());
			}
			break;
				
		case "business":
			if(localRandom < 0.02 ) {
				tripStartTime = (int) Math.round(-0.5 + 5 * 3600 * random.nextDouble());
			}
			if(localRandom >= 0.02 && localRandom < 0.15 ) {
				tripStartTime = 5 * 3600 + (int)Math.round(-0.5 + 3 * 3600 * random.nextDouble());
			}
			if(localRandom >= 0.15 && localRandom < 0.33 ) {
				tripStartTime = 8 * 3600 + (int)Math.round(-0.5 + 2 * 3600 * random.nextDouble());
			}
			if(localRandom >= 0.33 && localRandom < 0.58 ) {
				tripStartTime = 10 * 3600 + (int)Math.round(-0.5 + 3 * 3600 * random.nextDouble());
			}
			if(localRandom >= 0.58 && localRandom < 0.8 ) {
				tripStartTime = 13 * 3600 + (int)Math.round(-0.5 + 3 * 3600 * random.nextDouble());
			}
			if(localRandom >= 0.8 && localRandom < 0.95 ) {
				tripStartTime = 16 * 3600 + (int)Math.round(-0.5 + 3 * 3600 * random.nextDouble());
			}
			if(localRandom >= 0.95 ) {
				tripStartTime = 19 * 3600 + (int)Math.round(-0.5 + 3 * 3600 * random.nextDouble());
			}
			break;
			
		case "holiday":
			if(localRandom < 0.03 ) {
				tripStartTime = (int) Math.round(-0.5 + 5 * 3600 * random.nextDouble());
			}
			if(localRandom >= 0.03 && localRandom < 0.14 ) {
				tripStartTime = 5 * 3600 + (int)Math.round(-0.5 + 3 * 3600 * random.nextDouble());
			}
			if(localRandom >= 0.14 && localRandom < 0.26 ) {
				tripStartTime = 8 * 3600 + (int)Math.round(-0.5 + 2 * 3600 * random.nextDouble());
			}
			if(localRandom >= 0.26 && localRandom < 0.46 ) {
				tripStartTime = 10 * 3600 + (int)Math.round(-0.5 + 3 * 3600 * random.nextDouble());
			}
			if(localRandom >= 0.46 && localRandom < 0.69 ) {
				tripStartTime = 13 * 3600 + (int)Math.round(-0.5 + 3 * 3600 * random.nextDouble());
			}
			if(localRandom >= 0.69 && localRandom < 0.92 ) {
				tripStartTime = 16 * 3600 + (int)Math.round(-0.5 + 3 * 3600 * random.nextDouble());
			}
			if(localRandom >= 0.92 ) {
				tripStartTime = 19 * 3600 + (int)Math.round(-0.5 + 3 * 3600 * random.nextDouble());
			}
			break;
			
		case "other":
			if(localRandom < 0.05 ) {
				tripStartTime = (int) Math.round(-0.5 + 5 * 3600 * random.nextDouble());
			}
			if(localRandom >= 0.05 && localRandom < 0.08 ) {
				tripStartTime = 5 * 3600 + (int)Math.round(-0.5 + 3 * 3600 * random.nextDouble());
			}
			if(localRandom >= 0.08 && localRandom < 0.16 ) {
				tripStartTime = 8 * 3600 + (int)Math.round(-0.5 + 2 * 3600 * random.nextDouble());
			}
			if(localRandom >= 0.16 && localRandom < 0.34 ) {
				tripStartTime = 10 * 3600 + (int)Math.round(-0.5 + 3 * 3600 * random.nextDouble());
			}
			if(localRandom >= 0.34 && localRandom < 0.58 ) {
				tripStartTime = 13 * 3600 + (int)Math.round(-0.5 + 3 * 3600 * random.nextDouble());
			}
			if(localRandom >= 0.58 && localRandom < 0.86 ) {
				tripStartTime = 16 * 3600 + (int)Math.round(-0.5 + 3 * 3600 * random.nextDouble());
			}
			if(localRandom >= 0.86 ) {
				tripStartTime = 19 * 3600 + (int)Math.round(-0.5 + 3 * 3600 * random.nextDouble());
			}
			break;
		
		}
		
		
		return tripStartTime;
	}


	private static Coord getCoordInGeometry(String zone) {

		Geometry selectedLandcover = landcoverPerZone.get(zone).sample();

		Point point = selectedLandcover.getInteriorPoint();
		CoordinateTransformation tf = TransformationFactory.getCoordinateTransformation("EPSG:31467", "EPSG:25832");
        //		if (counter < 100000) {
//		// if the landcoverPerZone feature is in the correct region generate a random coordinate within the bounding box of the
//		// landcoverPerZone feature. Repeat until a coordinate is found which is actually within the landcoverPerZone feature.
//
//			do {
//				Envelope envelope = selectedLandcover.getEnvelopeInternal();
//
//				x = envelope.getMinX() + envelope.getWidth() * random.nextDouble();
//				y = envelope.getMinY() + envelope.getHeight() * random.nextDouble();
//				point = geometryFactory.createPoint(new Coordinate(x, y));
//			} while (point == null || !selectedLandcover.contains(point));
			return tf.transform(MGC.point2Coord(point)); }
		
//		else {
//			return new Coord(region.getCentroid().getX(), region.getCentroid().getY() );
//		}
//	}

}
