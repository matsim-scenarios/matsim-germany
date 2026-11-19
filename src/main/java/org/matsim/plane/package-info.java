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
 * <h2>Vehicle types - default plane</h2>
 * <p>The ADRR Flight dataset contains an ICAO aircraft type code per connection, but the lookup
 * table mapping those codes to aircraft specifications is not freely available (costs several
 * hundred euros). As a temporary measure, all departures are assigned a single
 * {@code default-plane} vehicle type instead of type-specific vehicles. The passenger capacity
 * of that vehicle type can be configured via the {@code --default-passenger-capacity} argument.</p>
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
