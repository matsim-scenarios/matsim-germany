library("tidyverse")

# Temporary R script for testing the dataset
# TODO remove this sript for final PQ

statfor_flights <- read_csv("/Users/aleksander/Documents/VSP/Planes/EUROCONTROL Data/202312/Flights_20231201_20231231.csv.gz")

airports <- statfor_flights %>%
  group_by(ADEP) %>%
  summarize(lat=sample(`ADEP Latitude`, 1), lon=sample(`ADEP Longitude`, 1)) %>%
  filter(!is.na(lat) & !is.na(lon))

world_map <- map_data("world")
ggplot(airports) +
  geom_polygon(data = world_map, aes(x = long, y = lat, group = group), fill = "grey21", color = "grey21") +
  geom_point(aes(x = lon, y = lat), color="red", size=0.5) +
  scale_color_identity() +
  coord_fixed() +
  xlab("") +
  ylab("")


one_day_flight_plan <- statfor_flights %>%
  # Only use scheduled flights
  filter(`ICAO Flight Type` == "S") %>%
  # Change date columns types
  mutate(`FILED OFF BLOCK TIME` = as.POSIXct(`FILED OFF BLOCK TIME`, format = "%d-%m-%Y %H:%M:%S"),
         `FILED ARRIVAL TIME` = as.POSIXct(`FILED ARRIVAL TIME`, format = "%d-%m-%Y %H:%M:%S"),
         `ACTUAL OFF BLOCK TIME` = as.POSIXct(`ACTUAL OFF BLOCK TIME`, format = "%d-%m-%Y %H:%M:%S"),
         `ACTUAL ARRIVAL TIME` = as.POSIXct(`ACTUAL ARRIVAL TIME`, format = "%d-%m-%Y %H:%M:%S")) %>%
  # Sample day 06-12-2023 (Wednesday), with 1 hour shift due to time zones
  filter(
    `FILED OFF BLOCK TIME` >= as.POSIXct("05-12-2023 23:00:00", format = "%d-%m-%Y %H:%M:%S") &
    `FILED OFF BLOCK TIME` <  as.POSIXct("06-12-2023 23:00:00", format = "%d-%m-%Y %H:%M:%S")
  ) %>%
  select(ADEP, "ADEP Longitude", "ADEP Latitude", ADES, "ADES Longitude", "ADES Latitude", "FILED OFF BLOCK TIME", "FILED ARRIVAL TIME", "ACTUAL OFF BLOCK TIME", "ACTUAL ARRIVAL TIME", "AC Type", "Actual Distance Flown")
