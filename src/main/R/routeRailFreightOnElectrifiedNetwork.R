library(tidyverse)
library(tmap)
library(sf)

'relations_orig_astarlandmarks <- read.csv("~/git/shared-svn/projects/matsim-germany/zerocuts2/astarlandmarks/railfreight_electrified_route_analysis.csv")
relations_from_point_astarlandmarks <- relations_orig_astarlandmarks %>% mutate(detour_electrified_km = length_electrified_km - length_non_electrified_km,
                                                  detour_electrified_incl_proposed_km = length_electrified_incl_proposed_km - length_non_electrified_km) %>% 
  mutate(origin_cell_main_run_in_germany = as.logical(origin_cell_main_run_in_germany), 
         destination_cell_main_run_in_germany = as.logical(destination_cell_main_run_in_germany)) %>% 
  st_as_sf(coords = c("fromX","fromY"), crs = 25832)
negative_detour_astarlandmarks <- relations_from_point_astarlandmarks %>%
  filter(detour_electrified_km < 0)'

# relations_orig <- read.csv("~/git/shared-svn/projects/matsim-germany/zerocuts2/dijkstra/railfreight_electrified_route_analysis.csv")

relations_orig <- read.csv("~/git/shared-svn/projects/matsim-germany/zerocuts2/railfreight_routes_1pct_GermanOD_in_Germany_km_in_Germany_NetworkOutsideGermanyHandlingUSE_ELECTRIFIED_NETWORK_ONLY/railfreight_electrified_route_analysis.csv")
# boolean access in Germany, egress in Germany. In Mittelwert nur das Ende in Deutschland berücksichtigen
# umwegfaktor
# freight-rail_routes-on-electrified-network-analysis_1kmh.csv

relations <- relations_orig %>% 
  mutate(origin_cell_main_run_in_germany = as.logical(origin_cell_main_run_in_germany), 
         destination_cell_main_run_in_germany = as.logical(destination_cell_main_run_in_germany),
         detour_electrified_km = length_electrified_km - length_non_electrified_km,
         detour_electrified_incl_proposed_km = length_electrified_incl_proposed_km - length_non_electrified_km) %>% 
  mutate(tkm_shortest_route = tons_year * length_non_electrified_km,
        detour_electrified_tkm = detour_electrified_km * tons_year)

relations_from_point <- relations %>% 
  st_as_sf(coords = c("fromX","fromY"), crs = 25832)

relations_to_point <- relations %>% 
  st_as_sf(coords = c("toX","toY"), crs = 25832)

negative_detour <- relations_from_point %>%
  filter(detour_electrified_km < 0)

#relations_from_point %>% filter (person_id %in% negative_detour_astarlandmarks$person_id)
#negative_detour_astarlandmarks

# Check plausibility of goods flows on map
relations %>% summarise(tkm_shortest_route_sum = sum(tkm_shortest_route))
relations %>% filter(origin_cell_main_run_in_germany & destination_cell_main_run_in_germany) %>% 
  summarise(tkm_shortest_route_sum = sum(tkm_shortest_route))

tmap_mode("view")
relations_from_aggr <- relations_from_point %>% 
  group_by(fromLink, origin_cell_main_run_in_germany, destination_cell_main_run_in_germany) %>% 
  summarise(sum_tons = sum(tons_year),
          num_relations = n())

tm_shape(relations_from_aggr %>% group_by(fromLink) %>% summarise(sum_tons = sum(sum_tons), num_relations = sum(num_relations))) +
  tm_dots(size = "sum_tons")

tm_shape(relations_from_aggr %>% filter(origin_cell_main_run_in_germany & destination_cell_main_run_in_germany)) +
  tm_dots(size = "sum_tons")

relations_to_aggr <- relations_to_point %>% 
  group_by(toLink, origin_cell_main_run_in_germany, destination_cell_main_run_in_germany) %>% 
  summarise(sum_tons = sum(tons_year),
            num_relations = n())

tm_shape(relations_to_aggr %>% group_by(toLink) %>% summarise(sum_tons = sum(sum_tons), num_relations = sum(num_relations))) +
  tm_dots(size = "sum_tons")

tm_shape(relations_to_aggr %>% filter(origin_cell_main_run_in_germany & destination_cell_main_run_in_germany)) +
  tm_dots(size = "sum_tons")

# average detours
summary_all_germany <- relations %>% 
#  filter(origin_cell_main_run_in_germany & destination_cell_main_run_in_germany) %>% 
  summarise(number_relations = n(),
            sum_tons_year = sum(tons_year),
            sum_tkm_shortest_route = sum(tkm_shortest_route),
            sum_tkm_detour = sum(detour_electrified_km * tons_year),
            length_access_km_mean = mean(length_access_km),
            length_egress_km_mean = mean(length_egress_km),
    length_non_electrified_km_mean = mean(length_non_electrified_km),
            detour_electrified_km_mean = mean(detour_electrified_km),
    detour_electrified_incl_proposed_km_mean = mean(detour_electrified_incl_proposed_km),
    detour_electrified_tkm_mean = mean(detour_electrified_tkm))

summary_electrified_longer_germany <- relations %>% 
  filter(detour_electrified_km > 0.001) %>% 
#  filter(origin_cell_main_run_in_germany & destination_cell_main_run_in_germany) %>% 
  summarise(number_relations = n(),
            sum_tons_year = sum(tons_year),
            sum_tkm_shortest_route = sum(tkm_shortest_route),
            sum_tkm_detour = sum(detour_electrified_km * tons_year),
            length_access_km_mean = mean(length_access_km),
            length_egress_km_mean = mean(length_egress_km),
            length_non_electrified_km_mean = mean(length_non_electrified_km),
            detour_electrified_km_mean = mean(detour_electrified_km),
            detour_electrified_incl_proposed_km_mean = mean(detour_electrified_incl_proposed_km),
            detour_electrified_tkm_mean = mean(detour_electrified_tkm))

summary_electrified1km_longer_germany <- relations %>% 
  filter(detour_electrified_km > 1) %>% 
  filter(origin_cell_main_run_in_germany & destination_cell_main_run_in_germany) %>% 
  summarise(number_relations = n(),
            sum_tons_year = sum(tons_year),
            sum_tkm_shortest_route = sum(tkm_shortest_route),
            sum_tkm_detour = sum(detour_electrified_km * tons_year),
            length_access_km_mean = mean(length_access_km),
            length_egress_km_mean = mean(length_egress_km),
            length_non_electrified_km_mean = mean(length_non_electrified_km),
            detour_electrified_km_mean = mean(detour_electrified_km),
            detour_electrified_incl_proposed_km_mean = mean(detour_electrified_incl_proposed_km),
            detour_electrified_tkm_mean = mean(detour_electrified_tkm))

summary_electrified10km_longer_germany <- relations %>% 
  filter(detour_electrified_km > 10) %>% 
#  filter(origin_cell_main_run_in_germany & destination_cell_main_run_in_germany) %>% 
  summarise(number_relations = n(),
            sum_tons_year = sum(tons_year),
            sum_tkm_shortest_route = sum(tkm_shortest_route),
            sum_tkm_detour = sum(detour_electrified_km * tons_year),
            length_access_km_mean = mean(length_access_km),
            length_egress_km_mean = mean(length_egress_km),
            length_non_electrified_km_mean = mean(length_non_electrified_km),
            detour_electrified_km_mean = mean(detour_electrified_km),
            detour_electrified_incl_proposed_km_mean = mean(detour_electrified_incl_proposed_km),
            detour_electrified_tkm_mean = mean(detour_electrified_tkm))

summary_electrified50km_longer_germany <- relations %>% 
  filter(detour_electrified_km > 50) %>% 
#  filter(origin_cell_main_run_in_germany & destination_cell_main_run_in_germany) %>% 
  summarise(number_relations = n(),
            sum_tons_year = sum(tons_year),
            sum_tkm_shortest_route = sum(tkm_shortest_route),
            sum_tkm_detour = sum(detour_electrified_km * tons_year),
            length_access_km_mean = mean(length_access_km),
            length_egress_km_mean = mean(length_egress_km),
            length_non_electrified_km_mean = mean(length_non_electrified_km),
            detour_electrified_km_mean = mean(detour_electrified_km),
            detour_electrified_incl_proposed_km_mean = mean(detour_electrified_incl_proposed_km),
            detour_electrified_tkm_mean = mean(detour_electrified_tkm))

# umweg detour_ton_km betrachten und deren quantile und daran durchhangeln
quantiles_detour_electrified_tkm <- relations_from_point %>% 
#  filter(origin_cell_main_run_in_germany & destination_cell_main_run_in_germany) %>% 
  pull(detour_electrified_tkm) %>% 
  quantile(probs = c(0.0, 0.01, 0.1, 0.5, 0.9, 0.99, 1.0))

relations_99pct_detour_electrified_tkm_from_point <- relations_from_point %>% 
#  filter(origin_cell_main_run_in_germany & destination_cell_main_run_in_germany) %>% 
  filter(detour_electrified_tkm > quantiles_detour_electrified_tkm["99%"])

tm_shape(relations_99pct_detour_electrified_tkm_from_point %>% group_by(fromLink) %>% summarise(detour_electrified_tkm = sum(detour_electrified_tkm))) +
  tm_dots(size = "detour_electrified_tkm")

relations_99pct_detour_electrified_tkm_to_point <- relations_to_point %>% 
#  filter(origin_cell_main_run_in_germany & destination_cell_main_run_in_germany) %>% 
  filter(detour_electrified_tkm > quantiles_detour_electrified_tkm["99%"])

tm_shape(relations_99pct_detour_electrified_tkm_to_point %>% group_by(toLink) %>% summarise(detour_electrified_tkm = sum(detour_electrified_tkm))) +
  tm_dots(size = "detour_electrified_tkm")

# percentage of tkm, not of relations
tkm_percentage_detour_all <- summary_electrified_longer_germany %>% pull(sum_tkm_shortest_route) / summary_all_germany %>% pull(sum_tkm_shortest_route)
tkm_percentage_detour01km <- summary_electrified1km_longer_germany %>% pull(sum_tkm_shortest_route) / summary_all_germany %>% pull(sum_tkm_shortest_route)
tkm_percentage_detour10km <- summary_electrified10km_longer_germany %>% pull(sum_tkm_shortest_route) / summary_all_germany %>% pull(sum_tkm_shortest_route)
tkm_percentage_detour50km <- summary_electrified50km_longer_germany %>% pull(sum_tkm_shortest_route) / summary_all_germany %>% pull(sum_tkm_shortest_route)
tons_percentage_detour_all <- summary_electrified_longer_germany %>% pull(sum_tons_year) / summary_all_germany %>% pull(sum_tons_year)
tons_percentage_detour01km <- summary_electrified1km_longer_germany %>% pull(sum_tons_year) / summary_all_germany %>% pull(sum_tons_year)
tons_percentage_detour10km <- summary_electrified10km_longer_germany %>% pull(sum_tons_year) / summary_all_germany %>% pull(sum_tons_year)
tons_percentage_detour50km <- summary_electrified50km_longer_germany %>% pull(sum_tons_year) / summary_all_germany %>% pull(sum_tons_year)

# detours below 1km appear to be rather an artifact in the data (e.g. one track segment not correctly tagged) or shortcuts over non-electrified tracks in a station.
tkm_detour01km_as_percentage_of_total_tkm_shortest_route <- summary_electrified1km_longer_germany %>% pull(sum_tkm_detour) / summary_all_germany %>% pull(sum_tkm_shortest_route)
tkm_detour_all_as_percentage_of_total_tkm_shortest_route <- summary_all_germany %>% pull(sum_tkm_detour) / summary_all_germany %>% pull(sum_tkm_shortest_route)
summary_electrified1km_longer_germany %>% pull(sum_tkm_detour)
summary_all_germany %>% pull(sum_tkm_detour)
# but excluding detours below 1km has little influence over the overall data as seen above. So for the sake of simplicity we just keep them.


# Investigate longest detours of electrified network route in respect to total network
quantiles_detour_electrified_km <- relations_from_point %>% 
#  filter(origin_cell_main_run_in_germany & destination_cell_main_run_in_germany) %>% 
  pull(detour_electrified_km) %>% 
  quantile(probs = c(0.0, 0.01, 0.1, 0.5, 0.9, 0.99, 1.0))

relations_99pct_detour_electrified_km_from_point <- relations_from_point %>% 
#  filter(origin_cell_main_run_in_germany & destination_cell_main_run_in_germany) %>% 
  filter(detour_electrified_km > quantiles_detour_electrified_km["99%"])

tm_shape(relations_99pct_detour_electrified_km_from_point %>% group_by(fromLink) %>% summarise(sum_tons = sum(tons_year))) +
  tm_dots(size = "sum_tons")

relations_99pct_detour_electrified_km_to_point <- relations_to_point %>% 
#  filter(origin_cell_main_run_in_germany & destination_cell_main_run_in_germany) %>% 
  filter(detour_electrified_km > quantiles_detour_electrified_km["99%"])

tm_shape(relations_99pct_detour_electrified_km_to_point %>% group_by(toLink) %>% summarise(sum_tons = sum(tons_year))) +
  tm_dots(size = "sum_tons")

# Investigate longest access trips to electrified network
quantiles_access_electrified_km <- relations_from_point %>% 
#  filter(origin_cell_main_run_in_germany & destination_cell_main_run_in_germany) %>% 
  pull(length_access_km) %>% 
  quantile(probs = c(0.0, 0.01, 0.1, 0.5, 0.9, 0.99, 1.0))

relations_99pct_access_electrified_km <- relations_from_point %>% 
#  filter(origin_cell_main_run_in_germany & destination_cell_main_run_in_germany) %>% 
  filter(length_access_km > quantiles_access_electrified_km["90%"])

tm_shape(relations_99pct_access_electrified_km) +
  tm_dots(size = "tons_year")

relations_99pct_access_electrified_km %>% 
  group_by(goods_type) %>% 
  summarise (tons_year_sum=sum(tons_year)) %>% 
  arrange(-tons_year_sum)
  
# tons   | goods types
# 179725 | 190 Nicht identifizierbare Güter: Güter, die sich aus irgendeinem Grund nicht genau bestim-men lassen und daher nicht den Gruppen 01-16 zugeordnet werden können
# 154149 | 140 Sekundärrohstoffe; kommunale Abfälle und sonstige Abfälle
# 147043 | 33 Steine und Erden, sonstige Bergbauerzeug-nisse
#  95240 | 160 Geräte und Material für die Güterbeförderung
#  36972 | 120 Fahrzeuge

relations %>% 
  group_by(goods_type) %>% 
  summarise (tons_year_sum=sum(tons_year)) %>% 
  arrange(-tons_year_sum)

#   goods_type tons_year_sum
#  1        190      71241016
#2        100      61922786
#3         72      37961303
#4         21      30400927
#5         80      29910064
#6         33      27298533
#7         31      20404364
#8        140      15231483

# ggf. eher Landkreise durchgehen von oben runter bis wir Schwedt erreichen. in supplementary material
relations_from_point %>% 
  filter(origin_cell_main_run_in_germany) %>% 
  group_by(origin_cell_main_run, length_access_km) %>% 
  summarise(tons_year_sum = sum(tons_year)) %>% 
  arrange(-length_access_km)

# auch in supplementary material Screenshot Anbindung an CZ statt DE elektrifiziertes Netz Problem

