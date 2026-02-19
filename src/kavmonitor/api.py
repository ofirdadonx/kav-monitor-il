import requests
from datetime import datetime, timedelta, timezone
from .config import BASE_API_URL, REQUEST_TIMEOUT
from .utils import log_step, log_api

# --- API Fetching Functions ---

def get_routes(line_number, operator):
    """Gets the routes for a given line number and operator from the GTFS API, ensuring uniqueness."""
    log_step(f"Fetching routes for line {line_number}")
    url = f"{BASE_API_URL}/gtfs_routes/list?route_short_name={line_number}&operator_refs={operator}&order_by=date%20desc&limit=100"
    log_api(url)
    try:
        response = requests.get(url, timeout=REQUEST_TIMEOUT)
        response.raise_for_status()
        
        all_routes = response.json()
        unique_routes = {}
        for route in all_routes:
            if route['route_long_name'] not in unique_routes:
                unique_routes[route['route_long_name']] = route
        
        print(f"Found {len(unique_routes)} unique routes.")
        return list(unique_routes.values())
        
    except requests.exceptions.RequestException as e:
        print(f"Error fetching routes: {e}")
        return None

def get_stops_for_route(selected_route):
    """Gets all stops for a given route, ordered by sequence. Resilient to missing data for current date."""
    log_step("Retrieving route map and stops")
    line_ref = selected_route['line_ref']
    operator_ref = selected_route['operator_ref']
    direction = selected_route.get('route_direction')
    short_name = selected_route.get('route_short_name')
    
    try:
        url = (f"{BASE_API_URL}/gtfs_rides/list?"
               f"gtfs_route__line_ref={line_ref}&"
               f"gtfs_route__operator_ref={operator_ref}&"
               f"gtfs_route__route_short_name={short_name}&"
               f"gtfs_route__route_direction={direction}&"
               f"order_by=id%20desc&limit=20")
        
        log_api(url)
        response = requests.get(url, timeout=REQUEST_TIMEOUT)
        response.raise_for_status()
        rides = response.json()

        if not rides:
            print("No rides found for this line.")
            return None

        ride_stops = []
        for ride in rides:
            temp_ride_id = ride['id']
            stops_url = f"{BASE_API_URL}/gtfs_ride_stops/list?gtfs_ride_ids={temp_ride_id}&order_by=stop_sequence"
            log_api(stops_url)
            stops_response = requests.get(stops_url, timeout=REQUEST_TIMEOUT)
            stops_response.raise_for_status()
            temp_stops = stops_response.json()
            
            if temp_stops:
                ride_stops = temp_stops
                print(f"Found valid route map with {len(ride_stops)} stops.")
                break
        
        if not ride_stops:
            print("Could not find any recent rides with stop data.")
            return None

        stops = []
        for rs in ride_stops:
            stops.append({
                'stop_name': rs.get('gtfs_stop__name'),
                'lat': rs.get('gtfs_stop__lat'),
                'lon': rs.get('gtfs_stop__lon'),
                'dist': rs.get('shape_dist_traveled')
            })
        return stops

    except requests.exceptions.RequestException as e:
        print(f"Error fetching stops: {e}")
        return None


def get_real_time_vehicle_data(selected_route, total_route_distance=None, quiet=False):
    """Gets deduplicated real-time vehicle data, filtered by distance if provided."""
    if not quiet:
        log_step("Querying live GPS locations")
    
    line_ref = selected_route['line_ref']
    operator_ref = selected_route['operator_ref']
    
    url = (f"{BASE_API_URL}/siri_vehicle_locations/list?"
           f"siri_routes__line_ref={line_ref}&"
           f"siri_routes__operator_ref={operator_ref}&"
           f"order_by=id%20desc&"
           f"limit=100")
    
    if not quiet:
        log_api(url)
    
    try:
        response = requests.get(url, timeout=REQUEST_TIMEOUT)
        response.raise_for_status()
        raw_data = response.json()
        
        now = datetime.now(timezone.utc)
        active_buses = {}
        
        for v in raw_data:
            vid = v.get('siri_ride__vehicle_ref')
            recorded_at_str = v.get('recorded_at_time')
            dist_from_start = v.get('distance_from_journey_start')
            
            if vid and recorded_at_str:
                recorded_at = datetime.fromisoformat(recorded_at_str)
                # Ensure the ping is from the last 60 minutes
                if (now - recorded_at).total_seconds() < 3600:
                    # If total distance is known, filter out buses that are way off-route
                    if total_route_distance and dist_from_start is not None:
                        if dist_from_start > total_route_distance * 1.1:
                            continue # Skip this record, bus is likely on a different trip
                            
                    if vid not in active_buses or recorded_at > active_buses[vid]['time']:
                        active_buses[vid] = {
                            'time': recorded_at,
                            'data': v
                        }
        
        deduplicated_list = [item['data'] for item in active_buses.values()]
        if not quiet:
            print(f"Detected {len(deduplicated_list)} active buses on this route.")
        return deduplicated_list

    except requests.exceptions.RequestException as e:
        if not quiet:
            print(f"Error fetching real-time data: {e}")
        return []

def get_upcoming_departures(selected_route):
    """Fetches next 3 scheduled departures, including those up to 20m in the past to catch delays."""
    log_step("Fetching upcoming schedule")
    line_ref = selected_route['line_ref']
    operator_ref = selected_route['operator_ref']
    direction = selected_route.get('route_direction')
    short_name = selected_route.get('route_short_name')
    
    # Look back 20 minutes to handle delayed buses
    now = datetime.now()
    start_lookback = now - timedelta(minutes=20)
    current_clock_time = now.strftime('%H:%M:%S')
    lookback_clock_time = start_lookback.strftime('%H:%M:%S')
    
    try:
        url = (f"{BASE_API_URL}/gtfs_rides/list?"
               f"gtfs_route__line_ref={line_ref}&"
               f"gtfs_route__operator_ref={operator_ref}&"
               f"gtfs_route__route_short_name={short_name}&"
               f"gtfs_route__route_direction={direction}&"
               f"order_by=id%20desc&limit=200")
        
        log_api(url)
        response = requests.get(url, timeout=REQUEST_TIMEOUT)
        response.raise_for_status()
        rides = response.json()
        
        if not rides:
            print("No scheduled rides found for this route.")
            return []

        unique_times = set()
        for ride in rides:
            start_time_str = ride.get('start_time')
            if start_time_str:
                utc_time = datetime.fromisoformat(start_time_str)
                local_time = utc_time.astimezone()
                unique_times.add(local_time.strftime('%H:%M:%S'))

        if not unique_times:
            print("Static schedule data is currently unavailable.")
            return []

        # Get times that are either in the future or in the recent 20m lookback
        upcoming_times = sorted([t for t in unique_times if t >= lookback_clock_time])[:3]

        if not upcoming_times:
            print(f"No scheduled departures found around the current time.")
            return []

        print(f"Tracking schedule window (from {lookback_clock_time}):")
        for t in upcoming_times:
            status = "(Delayed?)" if t < current_clock_time else ""
            print(f"- {t} {status}")
        
        return upcoming_times

    except requests.exceptions.RequestException as e:
        print(f"Error fetching departures: {e}")
        return []
    except ValueError as e:
        print(f"Error parsing time: {e}")
        return []
