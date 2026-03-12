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
import org.matsim.api.core.v01.network.Network;
import org.matsim.application.MATSimAppCommand;
import org.matsim.application.options.CrsOptions;
import org.matsim.contrib.osm.networkReader.OsmRailwayReader;
import org.matsim.contrib.osm.networkReader.OsmTags;
import org.matsim.contrib.osm.networkReader.SupersonicOsmNetworkReader;
import org.matsim.core.config.groups.NetworkConfigGroup;
import org.matsim.core.network.NetworkUtils;
import org.matsim.core.network.filter.NetworkFilterManager;
import org.matsim.core.network.io.NetworkWriter;
import org.matsim.core.scenario.ProjectionUtils;
import org.matsim.core.utils.geometry.CoordinateTransformation;
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

	// https://svn.vsp.tu-berlin.de/repos/public-svn/matsim/scenarios/countries/de/german-wide-freight/v2/ has a network
	// which differs from the network created here. We don't know

	@CommandLine.Option(names = "--input-pbf-roads", description = "input pbf file for road network", required = true,
		defaultValue = "../shared-svn/projects/matsim-germany/maps/processed/europe-2026-03-04-roads-coarse_only-germany-detailed.osm.pbf")
	private Path inputPbfRoads;

	@CommandLine.Option(names = "--input-pbf-railways", description = "input pbf file for railway network", required = true,
		defaultValue = "../shared-svn/projects/matsim-germany/maps/processed/europe-2026-03-04-railways.osm.pbf")
	private Path inputPbfRailways;

	@CommandLine.Option(names = "--output", description = "output network file", required = true,
		defaultValue = "../shared-svn/projects/matsim-germany/maps/processed/europe-2026-03-04-railways.osm.pbf")
	private Path output;

	@CommandLine.Option(names = "--output-path-intermediate-results", description = "output directory where intermediate results, e.g. unfiltered rail network, are written.")
	private Path outputIntermediates;

	@CommandLine.Option(names = "--germanyShp", description = "germany boundaries shp for filtering.",
		defaultValue = "../shared-svn/projects/matsim-germany/shp/germany-area.shp")
	private Path germanyShp;

	@CommandLine.Mixin
	private CrsOptions crs = new CrsOptions();
	// Input CRS: WGS84 (EPSG:4326). Recommended target CRS: EPSG:25832 or EPSG:5677

	public static void main(String[] args) {
		new CreateNetworkFromOSM().execute(args);
	}

	@Override
	public Integer call() throws Exception {
		Set<String> modes = new HashSet<>(List.of(TransportMode.car));
		Network network = new SupersonicOsmNetworkReader.Builder()
			.setCoordinateTransformation(crs.getTransformation())
			.setPreserveNodeWithId(id -> id == 2)
			.setAfterLinkCreated((link, osmTags, isReverse) -> link.setAllowedModes(modes))
			.build()
			.read(inputPbfRoads.toString());

		NetworkUtils.cleanNetwork(network, modes);
		if (outputIntermediates != null) {
			new NetworkWriter(network).write(outputIntermediates.resolve("network-roads.xml.gz").toString());
		}
		log.info("Car network done.");

		CoordinateTransformation transformationOsmToMatsim = crs.getTransformation();

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

		OsmRailwayReader railwayReader = new OsmRailwayReader.Builder()
			.setCoordinateTransformation(transformationOsmToMatsim)
			.build();
		Network railwayNetwork = railwayReader.read(inputPbfRailways);
		ProjectionUtils.putCRS(railwayNetwork, crs.getTargetCRS());
		if (outputIntermediates != null) {
			new NetworkWriter(network).write(outputIntermediates.resolve("network-railways-unfiltered.xml.gz").toString());
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
			new NetworkWriter(network).write(outputIntermediates.resolve("network-railways-filtered.xml.gz").toString());
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
