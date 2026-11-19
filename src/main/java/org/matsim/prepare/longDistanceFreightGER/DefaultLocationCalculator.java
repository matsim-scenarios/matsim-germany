package org.matsim.prepare.longDistanceFreightGER;

import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVParser;
import org.apache.commons.csv.CSVRecord;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.geotools.api.feature.simple.SimpleFeature;
import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.Envelope;
import org.locationtech.jts.geom.Geometry;
import org.locationtech.jts.geom.Point;
import org.matsim.api.core.v01.Coord;
import org.matsim.api.core.v01.Id;
import org.matsim.api.core.v01.network.Link;
import org.matsim.api.core.v01.network.Network;
import org.matsim.application.options.ShpOptions;
import org.matsim.core.gbl.Gbl;
import org.matsim.core.network.NetworkUtils;
import org.matsim.core.scenario.ProjectionUtils;
import org.matsim.core.utils.geometry.CoordUtils;
import org.matsim.core.utils.geometry.CoordinateTransformation;
import org.matsim.core.utils.geometry.transformations.GeotoolsTransformation;
import org.matsim.core.utils.io.IOUtils;
import org.matsim.core.utils.misc.Counter;

import java.io.BufferedReader;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Determine the start and end locations of the freight trips based on the NUTS3 shape file and the land use data (e.g., industry, retail, commercial)
 */
class DefaultLocationCalculator implements FreightAgentGenerator.LocationCalculator {
	private final static Logger logger = LogManager.getLogger(DefaultLocationCalculator.class);
	// This CRS is used as a backup for the longitude, latitude coordinates for the international locations where we don't have a network.
	private static final String CRS_BACKUP_LONG_LAT = "EPSG:4326";
	private static final int MAX_LANDUSE_POINT_ATTEMPTS = 50;
	private final Random rnd = new Random(5678);
	private final String landuseShp;
	private final Set<String> landUseTypes;
	private final Network network;
	private final Map<String, List<LocationSource>> mapping = new HashMap<>();
	private final ShpOptions shp;
	private List<Link> carLinks = List.of();
	private static final String lookUpTablePath = "https://svn.vsp.tu-berlin.de/repos/public-svn/matsim/" +
		"scenarios/countries/de/german-wide-freight/v2/processed-data/complete-lookup-table.csv";

	private interface LocationSource {}

	private record LinkLocationSource(Id<Link> linkId, Coord coord, String source) implements LocationSource {}

	private record LanduseLocationSource(Geometry geometry, CoordinateTransformation toNetwork, String landuseType) implements LocationSource {}

	public DefaultLocationCalculator(Network network, String shpFilePath, String landuseShp, Set<String> landUseTypes) throws IOException {

//		this.shp = new ShpOptions(shpFilePath, "EPSG:4326", StandardCharsets.ISO_8859_1);
//		this.shp = new ShpOptions(shpFilePath, "EPSG:25832", StandardCharsets.ISO_8859_1);
		// would be far better to get the CRS directly out of the shapefile.
		// Is actually possible, as follows:
		this.shp = new ShpOptions(shpFilePath, null, StandardCharsets.ISO_8859_1);

		// Reading shapefile from URL may not work properly, therefore, users may need to download the shape file to the local directory
		this.landuseShp = landuseShp;
		this.landUseTypes = landUseTypes;
		this.network = network;
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

		String networkCrs = ProjectionUtils.getCRS(network);
		ShpOptions.Index shpIndex = shp.createIndex(networkCrs, "NUTS_ID", ft -> relevantNutsIds.contains(Objects.toString(ft.getAttribute("NUTS_ID"))));
		// This creates an index.  One will be able to put in coordinates, and get out NUTS_IDs.  Those NUTS_IDs can be filtered down to those which
		// are in relevantNutsIDs.  The query coordinate system is given.  The index will automatically transform it to the coordinate system of the
		// shapefile, and then do the query.  What comes back is an attribute value, which has nothing to do with a coordinate system.

		ShpOptions.Index landIndex = null;
		if (landuseShp != null) {
			logger.info("Reading land use data...");
			landIndex = new ShpOptions(landuseShp, null, StandardCharsets.UTF_8)
				.createIndex(networkCrs, "fclass", ft -> landUseTypes.contains(Objects.toString(ft.getAttribute("fclass"))));
			logger.info("Read {} features for landuse", landIndex.size());
			//TODO check if the land use reader functions properly
		}

		logger.info("Processing shape network and shapefile...");
		carLinks = network.getLinks().values().stream().filter(l -> l.getAllowedModes().contains("car"))
			.collect(Collectors.toList());
		Map<String, List<LocationSource>> nutsToLocationMapping = new HashMap<>();
		Counter linkCounter = new Counter("link: ", " of " + carLinks.size() );
		for (Link link : carLinks) {
			linkCounter.incCounter();
			String nutsId = shpIndex.query(link.getToNode().getCoord());
			if (nutsId != null) {
				nutsToLocationMapping.computeIfAbsent(nutsId, l -> new ArrayList<>())
					.add(new LinkLocationSource(link.getId(), link.getToNode().getCoord(), "network-link"));
			}
		}

		if (landIndex != null) {
			Map<String, List<LocationSource>> landuseToLocationMapping = createLanduseLocationMapping(shpIndex, landIndex, networkCrs);
			// When the landuse list is not empty, then we sample coordinates from landuse. Otherwise, we use the full link lists in the NUTS region.
			for (String nutsId : landuseToLocationMapping.keySet()) {
				nutsToLocationMapping.put(nutsId, landuseToLocationMapping.get(nutsId));
			}
		}
		logger.info("Network and shapefile processing complete!");

		logger.info("Computing mapping between Verkehrszelle and departure location...");
		try (BufferedReader reader = IOUtils.getBufferedReader(IOUtils.resolveFileOrResource(lookUpTablePath), StandardCharsets.UTF_8)) {
			CSVParser parser = CSVFormat.Builder.create(CSVFormat.DEFAULT).setDelimiter(';').setHeader()
				.setSkipHeaderRecord(true).get().parse(reader);
			CoordinateTransformation ct = new GeotoolsTransformation(CRS_BACKUP_LONG_LAT, ProjectionUtils.getCRS(network));
//			CoordinateTransformation ct = new GeotoolsTransformation("EPSG:4326", "EPSG:25832");
			// yyyy the second coordinate system should not be manually set here, but should come from the network. kai, aug'25

			Counter recordCounter = new Counter( "record: " );
			for (CSVRecord record : parser) {
				recordCounter.incCounter();
				String verkehrszelle = record.get(0);
				String nuts2021 = record.get(3);

				// (a) Under "normal" circumstances, we just memorize the mapping:
				if (!nuts2021.isEmpty() && nutsToLocationMapping.get(nuts2021) != null) {
					mapping.put(verkehrszelle, nutsToLocationMapping.get(nuts2021));
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

				Link backupLink = getNearestLink(transformedCoord);
				logger.info( "backupLink={}", backupLink );

				assert backupLink != null : "link closest to " + transformedCoord + "is null";
				Gbl.assertNotNull( backupLink ); // the "assert" keyword, used in the line before, has a tendency to not work.  kai, aug'25
				mapping.put(verkehrszelle, List.of(new LinkLocationSource(backupLink.getId(), transformedCoord, "backup-coordinate")));
			}
		}
		logger.info("Location generator is successfully created!");
	}

	/**
	 * Groups matching landuse polygons by NUTS region. The polygons are kept as geometries so that each generated trip
	 * can draw its own point before this point is snapped to the currently used network.
	 */
	private Map<String, List<LocationSource>> createLanduseLocationMapping(ShpOptions.Index shpIndex, ShpOptions.Index landIndex, String networkCrs) {
		Map<String, List<LocationSource>> landuseToLocationMapping = new HashMap<>();
		CoordinateTransformation landuseToNetwork = landIndex.getShp().createInverseTransformation(networkCrs);
		for (SimpleFeature feature : landIndex.getAllFeatures()) {
			Geometry geometry = (Geometry) feature.getDefaultGeometry();
			if (geometry == null || geometry.isEmpty()) {
				continue;
			}

			Point referencePoint = geometry.getInteriorPoint();
			Coord referenceCoord = landuseToNetwork.transform(new Coord(referencePoint.getX(), referencePoint.getY()));
			String nutsId = shpIndex.query(referenceCoord);
			if (nutsId == null) {
				continue;
			}

			String landuseType = Objects.toString(feature.getAttribute("fclass"), null);
			landuseToLocationMapping.computeIfAbsent(nutsId, l -> new ArrayList<>())
				.add(new LanduseLocationSource(geometry, landuseToNetwork, landuseType));
		}
		return landuseToLocationMapping;
	}

	/**
	 * Returns the network location for one Verkehrszelle. For landuse-backed locations, a landuse point is sampled only
	 * to find the nearest car link; the sampled coordinate is kept in the activity for later spatial processing.
	 */
	@Override
	public FreightAgentGenerator.Location getLocation(String verkehrszelle) {
		List<LocationSource> locations = mapping.get(verkehrszelle);
		if (locations == null || locations.isEmpty()) {
			throw new IllegalArgumentException("No location mapping for Verkehrszelle " + verkehrszelle);
		}

		LocationSource locationSource = locations.get(rnd.nextInt(locations.size()));
		if (locationSource instanceof LanduseLocationSource landuseLocationSource) {
			Coord coord = sampleLanduseCoord(landuseLocationSource);
			Link link = getNearestLink(coord);
			Gbl.assertNotNull(link);
			return new FreightAgentGenerator.Location(link.getId(), coord, "landuse", landuseLocationSource.landuseType());
		}

		LinkLocationSource linkLocationSource = (LinkLocationSource) locationSource;
		return new FreightAgentGenerator.Location(linkLocationSource.linkId(), linkLocationSource.coord(), linkLocationSource.source(), null);
	}

	/**
	 * Draws a random coordinate from the selected landuse polygon. If repeated random draws miss the polygon, the
	 * geometry's interior point is used as a robust fallback.
	 */
	private Coord sampleLanduseCoord(LanduseLocationSource locationSource) {
		Geometry geometry = locationSource.geometry();
		Envelope envelope = geometry.getEnvelopeInternal();
		for (int i = 0; i < MAX_LANDUSE_POINT_ATTEMPTS; i++) {
			double x = envelope.getMinX() + rnd.nextDouble() * envelope.getWidth();
			double y = envelope.getMinY() + rnd.nextDouble() * envelope.getHeight();
			Point point = geometry.getFactory().createPoint(new Coordinate(x, y));
			if (geometry.contains(point)) {
				return locationSource.toNetwork().transform(new Coord(x, y));
			}
		}

		Point fallbackPoint = geometry.getInteriorPoint();
		return locationSource.toNetwork().transform(new Coord(fallbackPoint.getX(), fallbackPoint.getY()));
	}

	/**
	 * Finds the nearest car link. MATSim's nearest-link lookup can return a non-car incident link, so this falls back to
	 * an explicit distance scan over car links when needed.
	 */
	private Link getNearestLink(Coord coord) {
		Link nearestLink = NetworkUtils.getNearestLink(network, coord);
		if (nearestLink != null && nearestLink.getAllowedModes().contains("car")) {
			return nearestLink;
		}

		Link nearestCarLink = null;
		double shortestDistance = Double.MAX_VALUE;
		for (Link link : carLinks) {
			double distance = CoordUtils.distancePointLinesegment(link.getFromNode().getCoord(), link.getToNode().getCoord(), coord);
			if (distance < shortestDistance) {
				shortestDistance = distance;
				nearestCarLink = link;
			}
		}
		return nearestCarLink != null ? nearestCarLink : nearestLink;
	}
}
