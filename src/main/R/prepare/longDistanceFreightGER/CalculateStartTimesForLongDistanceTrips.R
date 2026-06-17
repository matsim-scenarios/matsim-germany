# ============================================================
# BASt 2023 - hourly distribution of heavy vehicle traffic
# Motorways and federal roads are evaluated together.
# ============================================================

options(encoding = "UTF-8")

library(data.table)
library(ggplot2)

# ============================================================
# 1) CONFIGURATION
# ============================================================
# see: https://www.bast.de/DE/Themen/Digitales/HF_1/Massnahmen/verkehrszaehlung/Stundenwerte.html?
file_A <- "2023_A_S.zip"
file_B <- "2023_B_S.zip"

out_dir <- "output/calcStartTimes"

weekdays_only <- TRUE

# "tls_truck" = LoA + Lzg
# "sv_tls"    = LoA + Lzg + Bus
# "lkw_bast"  = Lkw_R1 + Lkw_R2
vehicle_def <- "tls_truck"

# Shift the observed hourly profile backwards to approximate tour start times.
# 0 = no shift, 1 = one hour earlier, 2 = two hours earlier.
# The current analysis assumes a 30-minute start shift.
tour_start_shift_hours <- 0.5

# Use a backward time window to distribute observed traffic volumes over
# plausible trip start hours.
# The current analysis assumes that each tour can be observed within a
# nine-hour driving window.
use_9h_start_estimate <- TRUE
max_driving_window_hours <- 9


# ============================================================
# 3) MAIN CALL
# ============================================================

results <- run_analysis(
  file_A = file_A,
  file_B = file_B,
  out_dir = out_dir,
  weekdays_only = weekdays_only,
  vehicle_def = vehicle_def,
  tour_start_shift_hours = tour_start_shift_hours,
  use_9h_start_estimate = use_9h_start_estimate,
  max_driving_window_hours = max_driving_window_hours
)


# ============================================================
# 2) FUNCTIONS
# ============================================================

run_analysis <- function(
  file_A,
  file_B,
  out_dir,
  weekdays_only = TRUE,
  vehicle_def = "tls_truck",
  tour_start_shift_hours = 0,
  use_9h_start_estimate = TRUE,
  max_driving_window_hours = 9
) {

  message("==== BASt-Auswertung startet ====")

  dir.create(out_dir, showWarnings = FALSE, recursive = TRUE)

  dt_A <- read_bast_file(file_A, "Autobahn")
  dt_B <- read_bast_file(file_B, "Bundesstrasse")

  dt <- data.table::rbindlist(list(dt_A, dt_B), fill = TRUE)
  dt <- data.table::as.data.table(dt)

  dt[, Stunde := as.integer(Stunde)]
  dt <- dt[!is.na(Stunde) & Stunde >= 1 & Stunde <= 24]

  # BASt encodes hour 01 as the interval from 00:00 to 01:00.
  dt[, hour := Stunde - 1]

  if (weekdays_only && "Wotag" %in% names(dt)) {
    dt <- dt[Wotag %in% 1:5]
  }

  if (weekdays_only && "Fahrtzw" %in% names(dt)) {
    dt <- dt[is.na(Fahrtzw) | Fahrtzw == "w"]
  }

  dt <- make_vehicle_count(dt, vehicle_def)

  # ----------------------------------------------------------
  # Analysis 1: volume-weighted distribution across all count stations.
  # ----------------------------------------------------------

  hourly_volume <- dt[
    ,
    list(vehicles = sum(count, na.rm = TRUE)),
    by = list(hour)
  ]

  hourly_volume[, share := vehicles / sum(vehicles)]
  hourly_volume[, share_pct := share * 100]
  hourly_volume <- hourly_volume[order(hour)]

  hourly_volume <- shift_profile(
    hourly_volume,
    tour_start_shift_hours
  )

  # ----------------------------------------------------------
  # Analysis 2: station/day-normalized distribution.
  # Each station-day contributes equally, independent of its absolute volume.
  # ----------------------------------------------------------

  dt_day <- dt[
    ,
    list(vehicles = sum(count, na.rm = TRUE)),
    by = list(Zst, Datum, hour)
  ]

  dt_day[
    ,
    day_total := sum(vehicles, na.rm = TRUE),
    by = list(Zst, Datum)
  ]

  dt_day <- dt_day[day_total > 0]
  dt_day[, share_day := vehicles / day_total]

  hourly_station_day <- dt_day[
    ,
    list(
      share = mean(share_day, na.rm = TRUE),
      n_station_days = .N
    ),
    by = list(hour)
  ]

  hourly_station_day[, share := share / sum(share)]
  hourly_station_day[, share_pct := share * 100]
  hourly_station_day <- hourly_station_day[order(hour)]

  hourly_station_day <- shift_profile(
    hourly_station_day,
    tour_start_shift_hours
  )

  # ----------------------------------------------------------
  # Analysis 3: estimate start times with a backward driving-time window.
  # ----------------------------------------------------------

  hourly_start_9h_volume <- NULL
  hourly_start_9h_station_day <- NULL

  if (use_9h_start_estimate) {

    hourly_start_9h_volume <- estimate_start_profile_window(
      hourly_flow = hourly_volume,
      window_hours = max_driving_window_hours
    )

    hourly_start_9h_station_day <- estimate_start_profile_window(
      hourly_flow = hourly_station_day,
      window_hours = max_driving_window_hours
    )
  }

  # ----------------------------------------------------------
  # Export
  # ----------------------------------------------------------

  data.table::fwrite(
    hourly_volume,
    file.path(out_dir, "bast_2023_hourly_lkw_volume_weighted.csv"),
    sep = ";"
  )

  data.table::fwrite(
    hourly_station_day,
    file.path(out_dir, "bast_2023_hourly_lkw_station_day_normalized.csv"),
    sep = ";"
  )

  if (use_9h_start_estimate) {

    data.table::fwrite(
      hourly_start_9h_volume,
      file.path(out_dir, "bast_2023_hourly_start_9h_volume_weighted.csv"),
      sep = ";"
    )

    data.table::fwrite(
      hourly_start_9h_station_day,
      file.path(out_dir, "bast_2023_hourly_start_9h_station_day_normalized.csv"),
      sep = ";"
    )
  }

  # ----------------------------------------------------------
  # Plots
  # ----------------------------------------------------------

  p1 <- ggplot(
    hourly_volume,
    aes(x = hour, y = share_pct)
  ) +
    geom_line(linewidth = 0.9) +
    geom_point(size = 2.8) +
    geom_text(
      aes(label = sprintf("%.1f%%", share_pct)),
      vjust = -0.7,
      size = 4.2
    ) +
    scale_x_continuous(breaks = 0:23) +
    scale_y_continuous(expand = expansion(mult = c(0.05, 0.15))) +
    labs(
      title = "BASt 2023 - Volume-weighted hourly distribution",
      subtitle = "Motorways and federal roads combined",
      x = "Hour of day",
      y = "Share [%]"
    ) +
    theme_bw(base_size = 16) +
    theme(
      plot.title = element_text(size = 18, face = "bold"),
      plot.subtitle = element_text(size = 15),
      axis.title = element_text(size = 16),
      axis.text = element_text(size = 13)
    )

  print(p1)

  ggsave(
    file.path(out_dir, "bast_2023_hourly_volume_weighted.png"),
    p1,
    width = 12,
    height = 7,
    dpi = 300
  )

  p2 <- ggplot(
    hourly_station_day,
    aes(x = hour, y = share_pct)
  ) +
    geom_line(linewidth = 0.9) +
    geom_point(size = 2.8) +
    geom_text(
      aes(label = sprintf("%.1f%%", share_pct)),
      vjust = -0.7,
      size = 4.2
    ) +
    scale_x_continuous(breaks = 0:23) +
    scale_y_continuous(expand = expansion(mult = c(0.05, 0.15))) +
    labs(
      title = "BASt 2023 - Station/day-normalized hourly distribution",
      subtitle = "Motorways and federal roads combined",
      x = "Hour of day",
      y = "Share [%]"
    ) +
    theme_bw(base_size = 16) +
    theme(
      plot.title = element_text(size = 18, face = "bold"),
      plot.subtitle = element_text(size = 15),
      axis.title = element_text(size = 16),
      axis.text = element_text(size = 13)
    )

  print(p2)

  ggsave(
    file.path(out_dir, "bast_2023_hourly_station_day_normalized.png"),
    p2,
    width = 12,
    height = 7,
    dpi = 300
  )

  if (use_9h_start_estimate) {

    p3 <- ggplot(
      hourly_start_9h_volume,
      aes(x = hour, y = share_pct)
    ) +
      geom_line(linewidth = 0.9) +
      geom_point(size = 2.8) +
      geom_text(
        aes(label = sprintf("%.1f%%", share_pct)),
        vjust = -0.7,
        size = 4.2
      ) +
      scale_x_continuous(breaks = 0:23) +
      scale_y_continuous(expand = expansion(mult = c(0.05, 0.15))) +
      labs(
        title = "BASt 2023 - Start-time estimate with 9-hour backward window",
        subtitle = "Based on the volume-weighted hourly distribution",
        x = "Estimated start hour",
        y = "Share [%]"
      ) +
      theme_bw(base_size = 16) +
      theme(
        plot.title = element_text(size = 18, face = "bold"),
        plot.subtitle = element_text(size = 15),
        axis.title = element_text(size = 16),
        axis.text = element_text(size = 13)
      )

    print(p3)

    ggsave(
      file.path(out_dir, "bast_2023_hourly_start_9h_volume_weighted.png"),
      p3,
      width = 12,
      height = 7,
      dpi = 300
    )

    p4 <- ggplot(
      hourly_start_9h_station_day,
      aes(x = hour, y = share_pct)
    ) +
      geom_line(linewidth = 0.9) +
      geom_point(size = 2.8) +
      geom_text(
        aes(label = sprintf("%.1f%%", share_pct)),
        vjust = -0.7,
        size = 4.2
      ) +
      scale_x_continuous(breaks = 0:23) +
      scale_y_continuous(expand = expansion(mult = c(0.05, 0.15))) +
      labs(
        title = "BASt 2023 - Start-time estimate with 9-hour backward window",
        subtitle = "Based on the station/day-normalized hourly distribution",
        x = "Estimated start hour",
        y = "Share [%]"
      ) +
      theme_bw(base_size = 16) +
      theme(
        plot.title = element_text(size = 18, face = "bold"),
        plot.subtitle = element_text(size = 15),
        axis.title = element_text(size = 16),
        axis.text = element_text(size = 13)
      )

    print(p4)

    ggsave(
      file.path(out_dir, "bast_2023_hourly_start_9h_station_day_normalized.png"),
      p4,
      width = 12,
      height = 7,
      dpi = 300
    )
  }

  print(hourly_volume)
  print(hourly_station_day)

  if (use_9h_start_estimate) {
    print(hourly_start_9h_volume)
    print(hourly_start_9h_station_day)
  }

  message("==== Fertig ====")

  invisible(
    list(
      hourly_volume = hourly_volume,
      hourly_station_day = hourly_station_day,
      hourly_start_9h_volume = hourly_start_9h_volume,
      hourly_start_9h_station_day = hourly_start_9h_station_day
    )
  )
}

read_bast_file <- function(path, road_type) {

  message("Lese: ", path)

  if (grepl("\\.zip$", path, ignore.case = TRUE)) {

    tmp <- tempfile("bast_zip_")
    dir.create(tmp)

    unzip(path, exdir = tmp)

    files <- list.files(
      tmp,
      pattern = "\\.(csv|txt)$",
      recursive = TRUE,
      full.names = TRUE
    )

    if (length(files) == 0) {
      stop("Keine CSV- oder TXT-Datei im ZIP gefunden: ", path)
    }

    data_file <- files[1]

    dt <- data.table::fread(
      data_file,
      sep = ";",
      encoding = "Latin-1"
    )

  } else {

    dt <- data.table::fread(
      path,
      sep = ";",
      encoding = "Latin-1"
    )
  }

  dt[, road_type := road_type]
  return(dt)
}

clean_count <- function(x) {
  x <- suppressWarnings(as.numeric(x))
  x[x < 0] <- NA_real_
  return(x)
}

sum_cols <- function(dt, cols) {

  existing <- intersect(cols, names(dt))

  if (length(existing) == 0) {
    return(rep(NA_real_, nrow(dt)))
  }

  rowSums(
    dt[
      ,
      lapply(.SD, clean_count),
      .SDcols = existing
    ],
    na.rm = TRUE
  )
}

make_vehicle_count <- function(dt, vehicle_def = "tls_truck") {

  if (vehicle_def == "tls_truck") {

    dt[
      ,
      count :=
        sum_cols(.SD, c("LoA_R1", "Lzg_R1")) +
          sum_cols(.SD, c("LoA_R2", "Lzg_R2"))
    ]
  }

  if (vehicle_def == "sv_tls") {

    dt[
      ,
      count :=
        sum_cols(.SD, c("LoA_R1", "Lzg_R1", "Bus_R1")) +
          sum_cols(.SD, c("LoA_R2", "Lzg_R2", "Bus_R2"))
    ]
  }

  if (vehicle_def == "lkw_bast") {

    dt[
      ,
      count :=
        sum_cols(.SD, c("Lkw_R1")) +
          sum_cols(.SD, c("Lkw_R2"))
    ]
  }

  dt[count < 0 | is.na(count), count := 0]
  return(dt)
}

shift_profile <- function(dt, shift_hours = 0) {

  if (shift_hours == 0) {
    return(dt)
  }

  shifted <- data.table::copy(dt)

  # Safety check: the profile shift operates on normalized shares.
  if (!"share" %in% names(shifted)) {
    stop("shift_profile erwartet eine Spalte 'share'")
  }

  # Split the shift into full hours and a fractional remainder.
  int_shift  <- floor(shift_hours)
  frac_shift <- shift_hours - int_shift

  # Apply the integer part first by wrapping around the 24-hour clock.
  shifted[, new_hour := (hour - int_shift) %% 24]
  shifted <- shifted[order(new_hour)]

  # If the shift is exactly a full number of hours, no interpolation is needed.
  if (frac_shift == 0) {
    shifted[, hour := new_hour]
    shifted[, share_pct := share * 100]
    shifted[, new_hour := NULL]
    return(shifted[order(hour)])
  }

  # Fractional shifts split each hourly share between adjacent earlier hours.
  result <- data.table::data.table(
    hour = 0:23,
    share = 0
  )

  for (i in seq_len(nrow(shifted))) {

    h <- shifted$new_hour[i]
    s <- shifted$share[i]

    h1 <- h %% 24
    h2 <- (h - 1) %% 24   # fruehere Stunde

    result[hour == h1, share := share + s * (1 - frac_shift)]
    result[hour == h2, share := share + s * frac_shift]
  }

  result[, share := share / sum(share)]
  result[, share_pct := share * 100]

  return(result[order(hour)])
}

estimate_start_profile_window <- function(
  hourly_flow,
  window_hours = 9
) {

  hourly_flow <- data.table::as.data.table(hourly_flow)

  if (!"share" %in% names(hourly_flow)) {
    stop("hourly_flow muss eine Spalte 'share' enthalten.")
  }

  start_profile <- data.table::data.table(
    hour = 0:23,
    start_weight = 0
  )

  for (i in seq_len(nrow(hourly_flow))) {

    obs_hour <- hourly_flow$hour[i]
    obs_share <- hourly_flow$share[i]

    possible_starts <- (obs_hour - seq(0, window_hours - 1)) %% 24

    # Without trip-level distances, assign the observed share uniformly to all
    # possible start hours inside the driving-time window.
    start_profile[
      hour %in% possible_starts,
      start_weight := start_weight + obs_share / window_hours
    ]
  }

  start_profile[, share := start_weight / sum(start_weight)]
  start_profile[, share_pct := share * 100]

  return(start_profile[order(hour)])
}
