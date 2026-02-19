from .utils import log_step, format_hebrew
from .api import get_real_time_vehicle_data

# --- User Input Functions ---

def get_line_and_operator():
    """Gets the bus line number and operator from the user."""
    log_step("Identify bus line and operator")
    line_number = input("Enter the bus line number: ")

    providers = {
        "1": {"name": "Egged", "ref": "3", "heb": "אגד"},
        "2": {"name": "Dan", "ref": "4", "heb": "דן"},
        "3": {"name": "Metropoline", "ref": "5", "heb": "מטרופולין"},
        "4": {"name": "Kavim", "ref": "15", "heb": "קווים"},
        "5": {"name": "Nativ Express", "ref": "16", "heb": "נתיב אקספרס"},
        "6": {"name": "Superbus", "ref": "18", "heb": "סופרבוס"},
        "7": {"name": "Electra-Afikim", "ref": "25", "heb": "אלקטרה-אפיקים"},
        "8": {"name": "Tnufa", "ref": "31", "heb": "תנופה"},
        "9": {"name": "Nazareth United Bus Services", "ref": "14", "heb": "ש.א.מ"},
        "10": {"name": "Egged Ta'avura", "ref": "30", "heb": "אגד תעבורה"},
    }

    print("\nAvailable Providers:")
    for key, provider in providers.items():
        print(f"{key}. {provider['name']} ({format_hebrew(provider['heb'])})")

    provider_choice = input("\nEnter the number of the provider: ")

    if provider_choice in providers:
        operator = providers[provider_choice]["ref"]
        print(f"Selected: Line {line_number}, Operator {providers[provider_choice]['name']}")
        return line_number, operator
    else:
        print("Invalid choice.")
        return None, None

def choose_direction(routes):
    """Lets the user choose a direction from a list of routes, showing active bus counts."""
    log_step("Choose direction")
    if not routes:
        print("No routes found for the given line number and operator.")
        return None

    print("Fetching live bus counts for each direction...")
    for i, route in enumerate(routes):
        route_name = route['route_long_name']
        
        # Get live bus count for this specific route direction
        live_buses = get_real_time_vehicle_data(route, quiet=True)
        bus_count = len(live_buses)
        count_text = f"[{bus_count} active]" if bus_count > 0 else "[No active buses]"

        if "<->" in route_name:
            origin, destination = route_name.split("<->", 1)
            if "-" in destination and destination.endswith("#"):
                parts = destination.split("-")
                if parts[-1].replace("#", "").isdigit():
                    destination = "-".join(parts[:-1])
            
            origin_heb = origin.strip()
            dest_heb = destination.strip()
            
            print(f"{i + 1}. Origin: {format_hebrew(origin_heb)} | Destination: {format_hebrew(dest_heb)} {count_text}")
        else:
            print(f"{i + 1}. {format_hebrew(route_name)} {count_text}")

    while True:
        try:
            choice_str = input("\nEnter the number of the direction: ")
            choice = int(choice_str)
            if 1 <= choice <= len(routes):
                selected = routes[choice - 1]
                route_name = selected['route_long_name']
                if "<->" in route_name:
                    origin, destination = route_name.split("<->", 1)
                    print(f"Selected: {origin.strip()} -> {destination.strip()}")
                else:
                    print(f"Selected: {route_name}")
                return selected
            else:
                print("Invalid choice. Please enter a number from the list.")
        except ValueError:
            print("Invalid input. Please enter a number.")

def select_flow():
    """Lets the user choose the desired application flow."""
    print("\n--- Choose Application Mode ---")
    print("1. See current driving buses")
    print("2. Monitor for the next 'new' bus to start")
    print("3. Show upcoming scheduled departure times")
    
    while True:
        choice = input("\nEnter your choice (1-3): ")
        if choice in ["1", "2", "3"]:
            return choice
        print("Invalid choice.")
