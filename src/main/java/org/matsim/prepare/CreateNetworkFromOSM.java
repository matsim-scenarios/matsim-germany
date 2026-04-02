/* *********************************************************************** *
 * project: org.matsim.*												   *
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

package org.matsim.prepare;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.geotools.api.feature.simple.SimpleFeature;
import org.geotools.api.referencing.FactoryException;
import org.geotools.api.referencing.operation.MathTransform;
import org.geotools.api.referencing.operation.TransformException;
import org.geotools.geometry.jts.JTS;
import org.geotools.referencing.CRS;
import org.locationtech.jts.geom.Geometry;
import org.matsim.api.core.v01.TransportMode;
import org.matsim.api.core.v01.network.Link;
import org.matsim.api.core.v01.network.Network;
import org.matsim.application.MATSimAppCommand;
import org.matsim.application.options.CrsOptions;
import org.matsim.contrib.osm.networkReader.OsmRailwayReader;
import org.matsim.contrib.osm.networkReader.OsmTags;
import org.matsim.contrib.osm.networkReader.SupersonicOsmNetworkReader;
import org.matsim.core.config.groups.NetworkConfigGroup;
import org.matsim.core.network.NetworkUtils;
import org.matsim.core.network.algorithms.MultimodalNetworkCleaner;
import org.matsim.core.network.filter.NetworkFilterManager;
import org.matsim.core.network.io.NetworkWriter;
import org.matsim.core.scenario.ProjectionUtils;
import org.matsim.core.utils.geometry.geotools.MGC;
import org.matsim.core.utils.gis.GeoFileReader;
import picocli.CommandLine;

import java.nio.file.Path;
import java.util.*;
import java.util.stream.Collectors;


/**
 * This class creates a coarse europe-wide road and railway network with more detail in Germany.
 * The germany-wide-freight v1 and v2 versions are not well documented and apparently used a similar approach to that implemented here.
 */
public class CreateNetworkFromOSM implements MATSimAppCommand {

	private static final Logger log = LogManager.getLogger(CreateNetworkFromOSM.class);

	@CommandLine.Option(names = "--input-pbf-roads", description = "input pbf file for road network", required = true,
		defaultValue = "../shared-svn/projects/matsim-germany/maps/processed/europe-2026-03-04-roads-coarse_only-germany-detailed.osm.pbf")
	private Path inputPbfRoads;

	@CommandLine.Option(names = "--input-pbf-railways", description = "input pbf file for railway network", required = true,
		defaultValue = "../shared-svn/projects/matsim-germany/maps/processed/europe-2026-03-04-railways.osm.pbf")
	private Path inputPbfRailways;

	@CommandLine.Option(names = "--output", description = "output network file", required = true,
		defaultValue = "../shared-svn/projects/matsim-germany/german-wide-freight-v3/before-calibration/german-wide-freight-v3-network.xml.gz")
	private Path output;

	@CommandLine.Option(names = "--output-path-intermediate-results", description = "output directory where intermediate results, e.g. unfiltered rail network, are written.",
		defaultValue = "../shared-svn/projects/matsim-germany/german-wide-freight-v3/before-calibration/")
	private Path outputIntermediates;

	@CommandLine.Option(names = "--germanyShp", description = "germany boundaries shp for filtering.",
		defaultValue = "../shared-svn/projects/matsim-germany/shp/germany-area.shp")
	private Path germanyShp;

	@CommandLine.Mixin
	private CrsOptions crs = new CrsOptions();
	// --input-crs=EPSG:4326 --target-crs=EPSG:25832

	public static final String RAIL_ELECTRIFIED = OsmTags.RAIL + "_electrified";
	public static final String RAIL_ELECTRIFIED_INCL_PROPOSED = OsmTags.RAIL + "_electrifiedInclProposed";

	public static void main(String[] args) {
		new CreateNetworkFromOSM().execute(args);
	}

	@Override
	public Integer call() throws Exception {
		GeoFileReader germanyShapeReader = new GeoFileReader();
		Collection<SimpleFeature> germanyFeatures = germanyShapeReader.readFileAndInitialize(germanyShp.toString());
		List<Geometry> germanyGeometries;
		try {
			MathTransform transform = CRS.findMathTransform(// germanyShapeReader.getCoordinateSystem()
				germanyShapeReader.getCoordinateSystem(), CRS.decode(crs.getTargetCRS())); //CRS.decode("EPSG:4326", true) , CRS.decode(UTM32nAsEpsg));
			germanyGeometries = germanyFeatures.stream().map(simpleFeature -> {
				try {
					return JTS.transform( (Geometry) simpleFeature.getDefaultGeometry(), transform);
				} catch (TransformException e) {
					throw new RuntimeException(e);
				}
			}).collect(Collectors.toList());
		} catch (FactoryException e) {
			throw new RuntimeException(e);
		}

		Set<String> modesRoadNetwork = new HashSet<>(List.of(TransportMode.car,TransportMode.ride,TransportMode.truck));
		Network network = new SupersonicOsmNetworkReader.Builder()
			.setCoordinateTransformation(crs.getTransformation())
			.setPreserveNodeWithId(id -> id == 2)
			.setAfterLinkCreated((link, osmTags, isReverse) -> link.setAllowedModes(modesRoadNetwork))
			.build()
			.read(inputPbfRoads.toString());

		NetworkUtils.cleanNetwork(network, modesRoadNetwork);
		ProjectionUtils.putCRS(network, crs.getTargetCRS());
		if (outputIntermediates != null) {
			new NetworkWriter(network).write(outputIntermediates.resolve("german-wide-freight-v3-network-roads.xml.gz").toString());
		}
		log.info("Car network done.");

		OsmRailwayReader railwayReader = new OsmRailwayReader.Builder()
			.setCoordinateTransformation(crs.getTransformation())
			.build();
		Network railwayNetwork = railwayReader.read(inputPbfRailways);
		ProjectionUtils.putCRS(railwayNetwork, crs.getTargetCRS());
		if (outputIntermediates != null) {
			new NetworkWriter(railwayNetwork).write(outputIntermediates.resolve("german-wide-freight-v3-network-railways-unfiltered.xml.gz").toString());
		}

		// Filter to mainline rail (removes tram, metros, narrow gauge etc.)
		NetworkFilterManager networkFilterManager = new NetworkFilterManager(railwayNetwork, new NetworkConfigGroup());
		Set<String> filterUsages = new HashSet<>();
		filterUsages.add("main"); // main railway lines
		filterUsages.add("branch"); // branch railway lines can be significant for freight and passenger traffic, e.g. Halberstadt-Blankenburg-Ruebeland
		filterUsages.add(""); // mostly tracks in stations. Excluding them disconnects many branch lines from the network, so the network cleaner would delete them
		networkFilterManager.addLinkFilter(l -> {
			if (l.getAttributes().getAttribute(NetworkUtils.TYPE).equals(OsmTags.RAIL)) {
				Object usageObj = l.getAttributes().getAttribute(OsmTags.USAGE);
				Object serviceObj = l.getAttributes().getAttribute(OsmTags.SERVICE);
				String usage = usageObj == null ? "null" : usageObj.toString();
				String service = serviceObj == null ? "null" : serviceObj.toString();
				// include all main line tracks in Europe
				// inside stations sometimes different main lines are interconnected only with service=crossover
				if (usage.equals("main") || service.equals("crossover")) {
					return true;
				}
				// inside Germany include all railway tracks
				if (germanyGeometries.stream().anyMatch(geometry -> geometry.contains(MGC.coord2Point(l.getFromNode().getCoord()))) ||
						germanyGeometries.stream().anyMatch(geometry -> geometry.contains(MGC.coord2Point(l.getToNode().getCoord())))) {
					return true;
				}
			}
			return false;
		});
		Network filteredRailwayNetwork = networkFilterManager.applyFilters();
		NetworkUtils.cleanNetwork(filteredRailwayNetwork, new HashSet<>(Collections.singletonList(OsmTags.RAIL)));
		if (outputIntermediates != null) {
			new NetworkWriter(filteredRailwayNetwork).write(outputIntermediates.resolve("german-wide-freight-v3-network-railways-filtered.xml.gz").toString());
		}

		// add transport modes railway_electrifiedInclProposed, railway_electrified
		for (Link link: filteredRailwayNetwork.getLinks().values()) {
			Object electrifiedObj = link.getAttributes().getAttribute(OsmTags.ELECTRIFIED);
			String electrified = electrifiedObj == null ? "null" : electrifiedObj.toString();
			if (electrified.matches("contact_line")) {
				/* There are very few sections with contact_line and (third) rail near Hennigsdorf and Birkenwerder which we could ignore for ZeroCuts
				 * Only heavy rail should use OsmTags.RAIL "rail" and heavy rail uses contact_line except for Southern England and S-Bahn in Berlin
				 * and Hamburg. Those S-Bahn systems are tagged as "light_rail" in osm (despite technically being "heavy rail". Therefore, we consider
				 * only "contact_line" here.
				 */
				Set<String> modes = new HashSet<>(link.getAllowedModes());
				modes.add(RAIL_ELECTRIFIED);
				modes.add(RAIL_ELECTRIFIED_INCL_PROPOSED);
				link.setAllowedModes(modes);
				continue;
			}
			Object proposedElectrifiedObj = link.getAttributes().getAttribute(OsmTags.PROPOSED + OsmTags.ELECTRIFIED);
			String proposedElectrified = proposedElectrifiedObj == null ? "null" : proposedElectrifiedObj.toString();
			if (proposedElectrified.equals("contact_line")) {
				Set<String> modes = new HashSet<>(link.getAllowedModes());
				modes.add(RAIL_ELECTRIFIED_INCL_PROPOSED);
				link.setAllowedModes(modes);
			}
		}

		// clean mode-specific networks to make them suitable for routing
		MultimodalNetworkCleaner networkCleaner = new MultimodalNetworkCleaner(filteredRailwayNetwork);
		networkCleaner.run(Set.of(RAIL_ELECTRIFIED));
		networkCleaner.run(Set.of(RAIL_ELECTRIFIED_INCL_PROPOSED));
		if (outputIntermediates != null) {
			new NetworkWriter(filteredRailwayNetwork).write(outputIntermediates.resolve("german-wide-freight-v3-network-railways-final.xml.gz").toString());
		}

		// copy filteredRailwayNetwork into network
		filteredRailwayNetwork.getNodes().values().forEach(n -> {
			// both networks have osm as source, so identical node ids should always point to the same node
			if (!network.getNodes().containsKey(n.getId())) {
				network.addNode(n);
			}
		});
		filteredRailwayNetwork.getLinks().values().forEach(l -> network.addLink(l));
		log.info("Railway network done.");

		// write out the network into a file
		new NetworkWriter(network).write(output.toString());

		return 0;
	}
}
