# Open Bus Stride API Search Guide

This document outlines the best practices and verified methods for searching and retrieving public transportation data from the [Open Bus Stride API](https://open-bus-stride-api.hasadna.org.il/docs).

## 1. API Endpoints Reference

### **1.1. Get Routes**
-   **Endpoint:** `/gtfs_routes/list`
-   **Method:** GET
-   **Description:** Fetches a list of routes matching a line number and operator.
-   **Parameters:**
    -   `route_short_name`: The bus line number.
    -   `operator_refs`: The operator ID (e.g., 3 for Egged).
    -   `order_by`: Set to `date desc` to ensure the most recent route definitions are used.
    -   `date`: (Optional) The specific date for which to fetch the schedule.
-   **Example:** `https://open-bus-stride-api.hasadna.org.il/gtfs_routes/list?route_short_name=25&operator_refs=3&order_by=date%20desc`

### **1.2. Get Rides (Schedules)**
-   **Endpoint:** `/gtfs_rides/list`
-   **Method:** GET
-   **Description:** Fetches a list of scheduled rides for a given route.
-   **Parameters:**
    -   `gtfs_route_id`: The ID of the route.
    -   `gtfs_route__line_ref`: Filter by line reference.
    -   `gtfs_route__operator_ref`: Filter by operator reference.
    -   `gtfs_route__route_direction`: Filter by direction (e.g., '1' or '2').
    -   `start_time_from`: (Optional) ISO 8601 timestamp to filter rides starting after a certain time (e.g., `2026-02-18T00:00:00Z`).
    -   `order_by`: Set to `start_time` for chronological order or `id desc` for latest records.
    -   `limit`: The number of rides to return (use high limits like 200 for nightly deep scans).
-   **Example:** `https://open-bus-stride-api.hasadna.org.il/gtfs_rides/list?gtfs_route_id=15769&order_by=start_time&limit=5`

### **1.3. Get Ride Stops**
-   **Endpoint:** `/gtfs_ride_stops/list`
-   **Method:** GET
-   **Description:** Fetches the sequence of stops for a single ride.
-   **Parameters:**
    -   `gtfs_ride_ids`: The ID of the ride.
    -   `order_by`: The field to sort by (e.g., `stop_sequence`).
-   **Example:** `https://open-bus-stride-api.hasadna.org.il/gtfs_ride_stops/list?gtfs_ride_ids=21342673&order_by=stop_sequence`

### **1.4. Get Real-Time Vehicle Locations (SIRI)**
-   **Endpoint:** `/siri_vehicle_locations/list`
-   **Method:** GET
-   **Description:** Fetches real-time locations of vehicles for a specific bus line.
-   **Parameters:**
    -   `siri_routes__line_ref`: The line reference number.
    -   `siri_routes__operator_ref`: The operator reference number.
    -   `recorded_at_time_from`: ISO 8601 timestamp to filter for recent updates.
    -   `order_by`: Set to `recorded_at_time desc`.
    -   `limit`: Sensible limit (e.g., 500) to prevent overhead.
-   **Example:** `https://open-bus-stride-api.hasadna.org.il/siri_vehicle_locations/list?siri_routes__line_ref=10217&siri_routes__operator_ref=3&limit=100`

---

## 2. The Line Identification Challenge
A common pitfall is assuming that `line_ref` is a unique identifier for a bus line. It is **not**. Multiple operators may use the same `line_ref` for completely different routes in different cities. Even within the same operator, `line_ref` can sometimes be reused.

### **Correct Identification Strategy**
To uniquely identify a specific bus line, you **must** use a combination of:
1.  `gtfs_route__route_short_name` (The public line number, e.g., "194").
2.  `gtfs_route__operator_ref` (The unique ID of the provider, e.g., "3" for Egged).
3.  `gtfs_route__route_direction` (To distinguish between opposite directions).

**Example for reliable schedule fetching:**
`GET /gtfs_rides/list?gtfs_route__route_short_name=194&gtfs_route__operator_ref=3&gtfs_route__route_direction=1&order_by=id desc&limit=200`

---

## 3. Handling Nightly Data Transitions ("Empty Records")
The API database undergoes maintenance and population usually between 12:00 AM and 5:00 AM. During this window, "shell" records for the new day are created but may not yet contain `start_time` or `stop_sequence` data.

### **Resilience Best Practices**
- **Deep Scanning**: When searching for rides (`/gtfs_rides/list`), use a high `limit` (e.g., 200) and `order_by=id desc`.
- **Validation**: Always iterate through results and skip any record where `start_time` is `null`.
- **Cross-Line Filtering**: Always include `route_short_name` in the query to ensure deep scans don't pick up unrelated lines sharing the same `line_ref`.

---

## 4. Real-time Vehicle Tracking (SIRI)
The SIRI endpoint is high-volume. The standard `recorded_at_time_from` filter can be extremely slow and frequently times out under load.

### **Robust Search Strategy (Recommended)**
Instead of time-based filtering, use the **ID Descending** approach:
1.  Query the last 100-200 records for the specific line and operator using `order_by=id desc`.
2.  Perform manual deduplication in your application logic by keeping only the most recent record for each unique `siri_ride__vehicle_ref`.
3.  Apply a "Freshness Check" in code (e.g., skip records where `recorded_at_time` is more than 60 minutes old).

**Optimized Query:**
`GET /siri_vehicle_locations/list?siri_routes__line_ref=[REF]&siri_routes__operator_ref=[OP]&order_by=id desc&limit=100`

---

## 5. Calculating Progress Percentage
To show where a bus is on its route:
1.  **Total Distance**: Get the `shape_dist_traveled` from the final stop of the GTFS ride.
2.  **Current Position**: Get the `distance_from_journey_start` from the SIRI vehicle location.
3.  **Formula**: `(distance_from_journey_start / total_route_distance) * 100`.
4.  **Filtering**: Only display vehicles where the distance is within a reasonable range (e.g., `< 110%`) of the total route length to filter out buses on return journeys or different variations.

---

## 6. Timezone Handling
The API returns all timestamps in **UTC** (ISO 8601 format with `+00:00`). 
- **Display**: Always convert to the user's local timezone (Israel Time is UTC+2 or UTC+3 depending on DST).
- **Comparison**: When filtering for "upcoming" buses, compare the current local clock time against the converted `start_time` of the rides.
