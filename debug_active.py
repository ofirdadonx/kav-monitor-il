import requests
from datetime import datetime

BASE_API_URL = "https://open-bus-stride-api.hasadna.org.il"
TOTAL_DIST = 25510 # Tel Aviv -> Rishon (Line 194)

def find_active_buses():
    url = f"{BASE_API_URL}/siri_vehicle_locations/list?siri_routes__line_ref=37121&siri_routes__operator_ref=3&order_by=id%20desc&limit=100"
    try:
        r = requests.get(url, timeout=20)
        data = r.json()
        active = {}
        for v in data:
            vid = v.get('siri_ride__vehicle_ref')
            dist = v.get('distance_from_journey_start')
            time = v.get('recorded_at_time')
            if vid and dist is not None:
                if vid not in active or time > active[vid]['recorded_at_time']:
                    active[vid] = v
        
        if not active:
            print("No active buses found for this direction.")
            return

        print(f"Found {len(active)} active buses on the Tel Aviv -> Rishon route:")
        for vid, v in sorted(active.items(), key=lambda x: x[1]['distance_from_journey_start'], reverse=True):
            pct = (v['distance_from_journey_start'] / TOTAL_DIST) * 100
            time_local = datetime.fromisoformat(v['recorded_at_time']).astimezone().strftime('%H:%M:%S')
            print(f"- Bus {vid}: {pct:.1f}% complete (Updated at {time_local})")
            
    except Exception as e:
        print(f"Error: {e}")

if __name__ == "__main__":
    find_active_buses()
