/**
 * Provides classes for integrating commercial air traffic into a MATSim scenario.
 *
 * <p>The main entry point is {@link org.matsim.plane.CreatePlaneSchedule}, which reads flight
 * schedule data and generates a MATSim transit schedule, network, and vehicle file representing
 * domestic and European flights.</p>
 *
 * <h2>Input data - EUROCONTROL ADRR</h2>
 * <p>Flight schedules are sourced from the <em>EUROCONTROL Aviation Data Repository for Research</em>
 * (ADRR), specifically the basic <em>Flight</em> dataset. The data is free for research and
 * development purposes, updated quarterly, and covers a full month released approximately two years
 * after the fact (e.g. the dataset for 12/2023 was released in 01/2026). Datasets are available at
 * the EUROCONTROL ADRR dashboard.</p>
 *
 * <p>The required input files are stored on the VSP shared-SVN server under:
 * {@code /projects/matsim-germany/planes}. Please note the README when using the files. <p/>
 *
 * <h2>Boarding time</h2>
 * <p>{@code CreatePlaneSchedule} accepts a boarding time parameter (in seconds). It is subtracted
 * from the scheduled departure time of each vehicle, so that agents must arrive at the airport
 * earlier than the actual flight departure (unlike bus or train departures, where arriving a few
 * seconds before suffices). For example, a boarding time of 3600 s shifts a departure at t=10000 s
 * to t=6400 s, forcing agents to be at the airport at least an hour before the flight leaves.</p>
 *
 * <h2>Aircraft types, operators, and performance</h2>
 * <p>Each value in the ADRR {@code AC Type} field creates a separate MATSim vehicle type. All types
 * use the capacity configured with {@code --default-passenger-capacity}. The {@code AC Operator}
 * and {@code AC Registration} values are stored as attributes on the corresponding transit line
 * and vehicle using the names {@code icaoOperator} and {@code aircraftRegistration}. The aircraft
 * type is also stored as {@code icaoAircraftType}. No additional aircraft or operator lookup files
 * are required.</p>
 *
 * <p>The maximum velocity of a vehicle type is estimated from the selected ADRR flights as the
 * maximum observed ratio of actual flight distance to filed block time for that aircraft type.
 * This is an empirical effective block velocity, not the aircraft's technical maximum velocity.
 * During QSim, the effective link speed is limited by both the link free speed and this vehicle
 * type maximum velocity. Public-transport routing continues to use the departure and arrival
 * offsets in the transit schedule.</p>
 *
 * <p>Flight transit routes, aircraft vehicle types, and all links in the aircraft network use the
 * {@code airplane} mode. Airport stop facilities have the attribute {@code type=airport}, which
 * allows the SwissRailRaptor intermodal stop finder to identify them. The generated files can
 * therefore be routed directly without rewriting their modes while merging them into a scenario.</p>
 *
 * <p>Before writing, the aircraft network is reduced to the links referenced by transit routes and
 * stop facilities in the generated schedule. Unreferenced links and orphaned nodes are removed,
 * while the transit schedule itself remains unchanged.</p>
 *
 * <h2>Geographic filtering</h2>
 * <p>Flights can be filtered independently by departure and destination using the
 * {@code --departure-area} and {@code --destination-area} arguments. Each argument accepts a
 * polygon file in Shapefile, GeoPackage, or zipped Shapefile format. Specifying the same country
 * geometry for both arguments selects domestic flights only. Specifying only one argument selects
 * all flights departing from or arriving in that area, respectively.</p>
 *
 * <h2>Airport network structure</h2>
 * <p>Each airport is represented by a fixed sequence of five links:</p>
 * <ol>
 *   <li><b>runwayInbound</b> - high-speed inbound runway link</li>
 *   <li><b>taxiInbound</b> - slow taxi from runway to terminal</li>
 *   <li><b>terminal</b> (named by ICAO code) - the terminal link; the transit stop facility is
 *       placed here</li>
 *   <li><b>taxiOutbound</b> - slow taxi from terminal to outbound runway</li>
 *   <li><b>runwayOutbound</b> - high-speed outbound runway link</li>
 * </ol>
 * <p>Transit stop facilities are non-blocking, so vehicles do not hold up other traffic while
 * boarding or alighting.</p>
 */
package org.matsim.plane;
