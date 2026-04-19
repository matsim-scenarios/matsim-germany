library(tidyverse)
library(tmap)
library(sf)

relations_orig_astarlandmarks <- read.csv("~/git/shared-svn/projects/matsim-germany/zerocuts2/astarlandmarks/railfreight_electrified_route_analysis.csv")
relations_from_point_astarlandmarks <- relations_orig_astarlandmarks %>% mutate(detour_electrified_km = length_electrified_km - length_non_electrified_km,
                                                  detour_electrified_incl_proposed_km = length_electrified_incl_proposed_km - length_non_electrified_km) %>% 
  mutate(origin_cell_main_run_in_germany = as.logical(origin_cell_main_run_in_germany), 
         destination_cell_main_run_in_germany = as.logical(destination_cell_main_run_in_germany)) %>% 
  st_as_sf(coords = c("fromX","fromY"), crs = 25832)
negative_detour_astarlandmarks <- relations_from_point_astarlandmarks %>%
  filter(detour_electrified_km < 0)

relations_orig <- read.csv("~/git/shared-svn/projects/matsim-germany/zerocuts2/dijkstra/railfreight_electrified_route_analysis.csv")
# boolean access in Germany, egress in Germany. In Mittelwert nur das Ende in Deutschland berücksichtigen
# umwegfaktor
# freight-rail_routes-on-electrified-network-analysis_1kmh.csv

relations_from_point <- relations_orig %>% mutate(detour_electrified_km = length_electrified_km - length_non_electrified_km,
                     detour_electrified_incl_proposed_km = length_electrified_incl_proposed_km - length_non_electrified_km) %>% 
  mutate(origin_cell_main_run_in_germany = as.logical(origin_cell_main_run_in_germany), 
         destination_cell_main_run_in_germany = as.logical(destination_cell_main_run_in_germany)) %>% 
  st_as_sf(coords = c("fromX","fromY"), crs = 25832)

relations_to_point <- relations_orig %>% mutate(detour_electrified_km = length_electrified_km - length_non_electrified_km,
                                                  detour_electrified_incl_proposed_km = length_electrified_incl_proposed_km - length_non_electrified_km) %>% 
  mutate(origin_cell_main_run_in_germany = as.logical(origin_cell_main_run_in_germany), 
         destination_cell_main_run_in_germany = as.logical(destination_cell_main_run_in_germany)) %>% 
  st_as_sf(coords = c("toX","toY"), crs = 25832)

negative_detour <- relations_from_point %>%
  filter(detour_electrified_km < 0)

relations_from_point %>% filter (person_id %in% negative_detour_astarlandmarks$person_id)
negative_detour_astarlandmarks

# Check plausibility of goods flows on map
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
summary_all_germany <- relations_from_point %>% 
  filter(origin_cell_main_run_in_germany & destination_cell_main_run_in_germany) %>% 
  summarise(number_relations = n(),
            sum_tons_year = sum(tons_year),
            length_access_km_mean = mean(length_access_km),
            length_egress_km_mean = mean(length_egress_km),
    length_non_electrified_km_mean = mean(length_non_electrified_km),
            detour_electrified_km_mean = mean(detour_electrified_km),
    detour_electrified_incl_proposed_km_mean = mean(detour_electrified_incl_proposed_km))

summary_electrified_longer_germany <- relations_from_point %>% 
  filter(detour_electrified_km > 0.001) %>% 
  filter(origin_cell_main_run_in_germany & destination_cell_main_run_in_germany) %>% 
  summarise(number_relations = n(),
            sum_tons_year = sum(tons_year),
            length_access_km_mean = mean(length_access_km),
            length_egress_km_mean = mean(length_egress_km),
            length_non_electrified_km_mean = mean(length_non_electrified_km),
            detour_electrified_km_mean = mean(detour_electrified_km),
            detour_electrified_incl_proposed_km_mean = mean(detour_electrified_incl_proposed_km) )

summary_electrified1km_longer_germany <- relations_from_point %>% 
  filter(detour_electrified_km > 1) %>% 
  filter(origin_cell_main_run_in_germany & destination_cell_main_run_in_germany) %>% 
  summarise(number_relations = n(),
            sum_tons_year = sum(tons_year),
            length_access_km_mean = mean(length_access_km),
            length_egress_km_mean = mean(length_egress_km),
            length_non_electrified_km_mean = mean(length_non_electrified_km),
            detour_electrified_km_mean = mean(detour_electrified_km),
            detour_electrified_incl_proposed_km_mean = mean(detour_electrified_incl_proposed_km) )

summary_electrified10km_longer_germany <- relations_from_point %>% 
  filter(detour_electrified_km > 10) %>% 
  filter(origin_cell_main_run_in_germany & destination_cell_main_run_in_germany) %>% 
  summarise(number_relations = n(),
            sum_tons_year = sum(tons_year),
            length_access_km_mean = mean(length_access_km),
            length_egress_km_mean = mean(length_egress_km),
            length_non_electrified_km_mean = mean(length_non_electrified_km),
            detour_electrified_km_mean = mean(detour_electrified_km),
            detour_electrified_incl_proposed_km_mean = mean(detour_electrified_incl_proposed_km) )

summary_electrified50km_longer_germany <- relations_from_point %>% 
  filter(detour_electrified_km > 50) %>% 
  filter(origin_cell_main_run_in_germany & destination_cell_main_run_in_germany) %>% 
  summarise(number_relations = n(),
            sum_tons_year = sum(tons_year),
            length_access_km_mean = mean(length_access_km),
            length_egress_km_mean = mean(length_egress_km),
            length_non_electrified_km_mean = mean(length_non_electrified_km),
            detour_electrified_km_mean = mean(detour_electrified_km),
            detour_electrified_incl_proposed_km_mean = mean(detour_electrified_incl_proposed_km) )


# Investigate longest detours of electrified network route in respect to total network
quantiles_detour_electrified_km <- relations_from_point %>% 
  filter(origin_cell_main_run_in_germany & destination_cell_main_run_in_germany) %>% 
  pull(detour_electrified_km) %>% 
  quantile(probs = c(0.0, 0.01, 0.1, 0.5, 0.9, 0.99, 1.0))

relations_99pct_detour_electrified_km_from_point <- relations_from_point %>% 
  filter(origin_cell_main_run_in_germany & destination_cell_main_run_in_germany) %>% 
  filter(detour_electrified_km > quantiles_detour_electrified_km["99%"])

tm_shape(relations_99pct_detour_electrified_km_from_point %>% group_by(fromLink) %>% summarise(sum_tons = sum(tons_year))) +
  tm_dots(size = "sum_tons")

relations_99pct_detour_electrified_km_to_point <- relations_to_point %>% 
  filter(origin_cell_main_run_in_germany & destination_cell_main_run_in_germany) %>% 
  filter(detour_electrified_km > quantiles_detour_electrified_km["99%"])

tm_shape(relations_99pct_detour_electrified_km_to_point %>% group_by(toLink) %>% summarise(sum_tons = sum(tons_year))) +
  tm_dots(size = "sum_tons")

# Investigate longest access trips to electrified network
quantiles_access_electrified_km <- relations_from_point %>% 
  filter(origin_cell_main_run_in_germany & destination_cell_main_run_in_germany) %>% 
  pull(length_access_km) %>% 
  quantile(probs = c(0.0, 0.01, 0.1, 0.5, 0.9, 0.99, 1.0))

relations_99pct_access_electrified_km <- relations_from_point %>% 
  filter(origin_cell_main_run_in_germany & destination_cell_main_run_in_germany) %>% 
  filter(length_access_km > quantiles_access_electrified_km["99%"])

tm_shape(relations_99pct_access_electrified_km) +
  tm_dots(size = "tons_year")

