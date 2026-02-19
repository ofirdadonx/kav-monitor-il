import time
from datetime import datetime
from kavmonitor.config import SOFT_POLL_INTERVAL_SECONDS, AGGRESSIVE_POLL_INTERVAL_SECONDS
from kavmonitor.utils import format_hebrew
from kavmonitor.api import (
    get_routes, get_stops_for_route, get_real_time_vehicle_data, get_upcoming_departures
)
from kavmonitor.cli import get_line_and_operator, choose_direction, select_flow
from kavmonitor.logic import is_location_between_stops

def main():
    print("--- Starting Kav Monitor IL ---")
    
    flow_choice = select_flow()
    line_number, operator = get_line_and_operator()
    
    if line_number and operator:
        routes = get_routes(line_number, operator)
        if routes:
            selected_route = choose_direction(routes)
            if not selected_route:
                return

            # Flow 3: Upcoming Times (Direct Exit)
            if flow_choice == "3":
                get_upcoming_departures(selected_route)
                return

            # Flow 1 & 2 both need stop data for distance/percentage
            stops = get_stops_for_route(selected_route)
            if not stops or len(stops) < 2:
                print("Could not retrieve enough stop data.")
                return

            origin_station = stops[0]
            second_station = stops[1]
            total_route_distance = stops[-1]['dist'] if stops[-1]['dist'] else 0

            # Flow 1: Current driving buses (Show once and exit)
            if flow_choice == "1":
                print("\n--------------------------------------------------")
                print(f"Active Buses Snapshot: {format_hebrew(selected_route['route_long_name'])}")
                print(f"Total route distance: {total_route_distance} meters")
                print("--------------------------------------------------")
                
                # Pass total_route_distance to filter out irrelevant buses
                real_time_data = get_real_time_vehicle_data(selected_route, total_route_distance=total_route_distance)
                active_found = False
                if real_time_data:
                    for vehicle in real_time_data:
                        vehicle_id = vehicle.get('siri_ride__vehicle_ref')
                        dist_from_start = vehicle.get('distance_from_journey_start')
                        lat = vehicle.get('lat')
                        lon = vehicle.get('lon')
                        start_time_raw = vehicle.get('siri_ride__scheduled_start_time')
                        recorded_at_raw = vehicle.get('recorded_at_time')
                        
                        if vehicle_id and dist_from_start is not None and total_route_distance > 0:
                            percentage = min(100, (dist_from_start / total_route_distance) * 100)
                            
                            # Format start time and last update time
                            start_time_local = "N/A"
                            if start_time_raw:
                                start_time_local = datetime.fromisoformat(start_time_raw).astimezone().strftime('%H:%M:%S')
                            
                            last_update_local = "N/A"
                            if recorded_at_raw:
                                last_update_local = datetime.fromisoformat(recorded_at_raw).astimezone().strftime('%H:%M:%S')
                            
                            # Find next stop
                            next_stop_name = "N/A"
                            for s in stops:
                                if s.get('dist') and s['dist'] > dist_from_start:
                                    next_stop_name = format_hebrew(s['stop_name'])
                                    break
                            
                            print(f"\n[ Bus {vehicle_id} ]")
                            print(f"  Started at:   {start_time_local}")
                            print(f"  Last Update:  {last_update_local}")
                            print(f"  Next Stop:    {next_stop_name}")
                            print(f"  Completion:   {percentage:.1f}%")
                            print(f"  Map Point:    {lat}, {lon}")
                            active_found = True
                
                if not active_found:
                    print("\nNo active buses matching this direction currently detected.")
                return

            # Flow 2: Monitor for new bus (Continuous loop)
            if flow_choice == "2":
                upcoming_times = get_upcoming_departures(selected_route)
                if not upcoming_times:
                    print("No upcoming departures detected. Exiting tracker.")
                    return

                print("\n--------------------------------------------------")
                print(f"Monitoring for NEW bus starting from: {format_hebrew(origin_station['stop_name'])}")
                print("--------------------------------------------------")

                while True:
                    now = datetime.now()
                    current_clock_time = now.strftime('%H:%M:%S')
                    
                    real_time_data = get_real_time_vehicle_data(selected_route, total_route_distance=total_route_distance, quiet=True)
                    bus_found_and_started = False
                    any_bus_near_start = False
                    
                    # Track which scheduled times are already "on the road"
                    consumed_times = set()
                    if real_time_data:
                        for vehicle in real_time_data:
                            vehicle_id = vehicle.get('siri_ride__vehicle_ref')
                            dist_from_start = vehicle.get('distance_from_journey_start')
                            sched_start_raw = vehicle.get('siri_ride__scheduled_start_time')
                            
                            if vehicle_id and dist_from_start is not None and total_route_distance > 0:
                                percentage = (dist_from_start / total_route_distance) * 100
                                
                                # If this bus is linked to a schedule, check if it's already "gone"
                                if sched_start_raw:
                                    sched_local = datetime.fromisoformat(sched_start_raw).astimezone().strftime('%H:%M:%S')
                                    if percentage > 5:
                                        consumed_times.add(sched_local)

                                if percentage < 5:
                                    any_bus_near_start = True

                            if is_location_between_stops(vehicle, origin_station, second_station):
                                start_time_raw = vehicle.get('siri_ride__scheduled_start_time')
                                recorded_at_raw = vehicle.get('recorded_at_time')
                                percentage = (dist_from_start / total_route_distance) * 100 if total_route_distance > 0 else 0
                                
                                # Format times
                                start_time_local = "N/A"
                                if start_time_raw:
                                    start_time_local = datetime.fromisoformat(start_time_raw).astimezone().strftime('%H:%M:%S')
                                last_update_local = "N/A"
                                if recorded_at_raw:
                                    last_update_local = datetime.fromisoformat(recorded_at_raw).astimezone().strftime('%H:%M:%S')
                                
                                # Find next stop
                                next_stop_name = format_hebrew(second_station['stop_name'])
                                
                                print(f"\n[ALERT] - Bus {vehicle_id} has started its journey!")
                                print(f"  Origin:       {format_hebrew(origin_station['stop_name'])}")
                                print(f"  Started at:   {start_time_local}")
                                print(f"  Last Update:  {last_update_local}")
                                print(f"  Next Stop:    {next_stop_name}")
                                print(f"  Completion:   {percentage:.1f}%")
                                print(f"  Map Point:    {vehicle.get('lat')}, {vehicle.get('lon')}")
                                
                                bus_found_and_started = True
                                break

                    if bus_found_and_started:
                        break 
                    
                    # Find the first departure time that isn't already "done"
                    next_dep = None
                    for t_str in upcoming_times:
                        if t_str not in consumed_times:
                            # If it's in the future OR it's a recent past one that hasn't started yet
                            if t_str >= current_clock_time or t_str in upcoming_times: # all in upcoming_times are valid targets
                                next_dep = t_str
                                break
                    
                    if not next_dep:
                        print("All tracked scheduled departures have passed or are already on route. Exiting.")
                        break

                    dep_time = datetime.strptime(next_dep, '%H:%M:%S').replace(
                        year=now.year, month=now.month, day=now.day
                    )
                    time_diff = (dep_time - now).total_seconds() / 60
                    
                    # Phase check: Aggressive if < 5 mins to departure OR bus is sitting at terminal
                    is_near_departure = time_diff <= 5
                    
                    if is_near_departure or any_bus_near_start:
                        current_interval = AGGRESSIVE_POLL_INTERVAL_SECONDS
                        mode = "AGGRESSIVE (Bus start expected soon)"
                    else:
                        current_interval = SOFT_POLL_INTERVAL_SECONDS
                        mode = "SOFT (Next bus not yet near)"

                    print(f"Mode: {mode} | Target: {next_dep} ({time_diff:.1f}m) | Checking again in {current_interval}s...")
                    time.sleep(current_interval)

if __name__ == "__main__":
    main()
