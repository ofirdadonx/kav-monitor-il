# --- Logic Functions ---

def is_location_between_stops(location, stop1, stop2):
    """
    Checks if a location is within the geographical bounding box
    defined by two stops, or if distance traveled is between stop 1 and 2.
    """
    lat = location.get('lat')
    lon = location.get('lon')
    dist = location.get('distance_from_journey_start')

    if not lat or not lon:
        return False

    # Check coordinate bounding box
    lat_min = min(stop1['lat'], stop2['lat'])
    lat_max = max(stop1['lat'], stop2['lat'])
    lon_min = min(stop1['lon'], stop2['lon'])
    lon_max = max(stop1['lon'], stop2['lon'])
    
    in_box = lat_min <= lat <= lat_max and lon_min <= lon <= lon_max
    
    # Check distance traveled (if available)
    # If dist is between 1m and the 2nd station distance, it started.
    # We use 1m to avoid false positives from buses parked at terminal.
    in_dist_range = False
    if dist is not None and stop2.get('dist'):
        in_dist_range = 1 <= dist <= stop2['dist']

    return in_box or in_dist_range
