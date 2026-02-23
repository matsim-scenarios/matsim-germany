package org.matsim.car;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.geotools.api.feature.simple.SimpleFeature;
import org.geotools.api.referencing.FactoryException;
import org.geotools.api.referencing.operation.MathTransform;
import org.geotools.api.referencing.operation.TransformException;
import org.geotools.geometry.jts.JTS;
import org.geotools.referencing.CRS;
import org.locationtech.jts.geom.Geometry;
import org.matsim.api.core.v01.network.Network;
import org.matsim.contrib.osm.networkReader.OsmRailwayReader;
import org.matsim.contrib.osm.networkReader.OsmTags;
import org.matsim.core.config.groups.NetworkConfigGroup;
import org.matsim.core.network.NetworkUtils;
import org.matsim.core.network.filter.NetworkFilterManager;
import org.matsim.core.network.io.NetworkWriter;
import org.matsim.core.scenario.ProjectionUtils;
import org.matsim.core.utils.geometry.CoordinateTransformation;
import org.matsim.core.utils.geometry.geotools.MGC;
import org.matsim.core.utils.geometry.transformations.TransformationFactory;
import org.matsim.core.utils.gis.GeoFileReader;
import org.matsim.core.utils.io.OsmNetworkReader;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;
import java.util.stream.Collectors;


/**
 * This is the first approach to create a network from osm data. It was later enhanced with a railway network.
 * The germany-wide-freight v1 and v2 versions ignored this class and used ReadFreightNetwork instead.
 * There is no apparent reason why different networks should be used for any future Germany model and the long distance freight only model.
 * TODO: Merge both classes
 */
public class RunCreateNetworkFromOSM {

	private static final String UTM32nAsEpsg = "EPSG:25832";
	private static final Path input = Paths.get("../public-svn/matsim/scenarios/countries/de/germany/original_data/osm/germanyFilter.osm");
	private static final String inputRailwayNetwork = "/Users/gleich/Projekte/ZeroCutsBahn/Netzwerkmodell/europe-2026-01-19-after-fixes-in-osm.osm.pbf";// TODO: reduce size and upload osm pbf
	private static final Path germanyShp = Paths.get("../shared-svn/projects/matsim-germany/shp/germany-area.shp");
	private static final Logger log = LogManager.getLogger(RunCreateNetworkFromOSM.class);

	// https://svn.vsp.tu-berlin.de/repos/public-svn/matsim/scenarios/countries/de/german-wide-freight/v2/ has a network
	// which differs from the network created here. We don't know

	public static void main(String[] args) {
		new RunCreateNetworkFromOSM().create();
	}

	private void create() {

		// create an empty network which will contain the parsed network data
		Network network = NetworkUtils.createNetwork();

		CoordinateTransformation transformationOsmToMatsim = TransformationFactory.getCoordinateTransformation(
			TransformationFactory.WGS84, UTM32nAsEpsg
		);

		/*
		 * FIXME: The deprecated OsmNetworkReader adjusted link attributes (probably capacity, freespeed) to some vsp standards. The newer
		 * SupersonicOsmNetworkReader has options to modify these attributes, but apparently no direct substitution to set to the same standards.
		 * Newer scenarios create a sumo network and then translate that into matsim and modify attributes. Not sure what to do here, gleich may'25
		 */
		// create an osm network reader with a filter
		OsmNetworkReader reader = new OsmNetworkReader(network, transformationOsmToMatsim, true, true);

		// the actual work is done in this call. Depending on the data size this may take a long time
		reader.parse(input.toString());

		// clean the network to remove unconnected parts where agents might get stuck
		NetworkUtils.cleanNetwork(network, new HashSet<>(Collections.singletonList("car")));
		log.info("Car network done.");

		GeoFileReader germanyShapeReader = new GeoFileReader();
		Collection<SimpleFeature> germanyFeatures = germanyShapeReader.readFileAndInitialize(germanyShp.toString());
		List<Geometry> germanyGeometries;
		try {
			MathTransform transform = CRS.findMathTransform(// germanyShapeReader.getCoordinateSystem()
				CRS.decode("EPSG:4326", true) , CRS.decode(UTM32nAsEpsg));
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
		Network railwayNetwork = railwayReader.read(inputRailwayNetwork);
		ProjectionUtils.putCRS(railwayNetwork, UTM32nAsEpsg);
		new NetworkWriter(railwayNetwork).write("./output/network-railway-unfiltered.xml.gz");


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
		new NetworkWriter(filteredRailwayNetwork).write("./output/network-railway-filtered.xml.gz");

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
		new NetworkWriter(network).write("./output/network.xml.gz");
	}
}
