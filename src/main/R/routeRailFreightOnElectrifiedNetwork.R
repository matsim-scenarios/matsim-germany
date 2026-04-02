library(tidyverse)
library(tmap)
library(sf)

relations_orig <- read.csv("~/git/shared-svn/projects/matsim-germany/zerocuts2/freight-rail_routes-on-electrified-network-analysis.csv")
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

negative_detour_extreme <- relations_from_point %>%
  filter(origin_cell_main_run_in_germany & destination_cell_main_run_in_germany) %>% 
  filter(detour_electrified_km < -30)

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

