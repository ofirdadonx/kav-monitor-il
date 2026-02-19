# Bus Tracker Application Specifications

## 1. Overview
This document outlines the functional specifications for the Bus Tracker command-line application. The application allows users to track current buses, monitor for the next departure, or view upcoming schedules for a specific bus line.

## 2. Functional Requirements

### 2.1. Operation Modes
The application offers three distinct modes of operation:
1.  **See Current Driving Buses**: Displays a detailed snapshot of all buses currently active on the selected route. For each bus, it shows:
    -   **Vehicle ID** (Car Number)
    -   **Started At** (Actual/Scheduled start time in local time)
    -   **Next Stop** (The name of the next station based on distance)
    -   **Journey Completion** (Percentage of route finished)
    -   **Last Update** (The local time of the most recent GPS ping)
    -   **Map Point** (Current Lat/Lon coordinates)
2.  **Monitor for Next 'New' Bus**: Continuously polls for the next bus to start its route from the origin terminal.
3.  **Show Upcoming Schedule**: Displays the next 3 scheduled departure times for the selected route.

### 2.2. User Input
- The application will prompt the user to choose an **Operation Mode**.
- The application will prompt the user to select a bus provider (e.g., Egged, Dan).
- The application will then prompt the user to enter a bus line number.
- For each direction, the application will display the origin and destination terminals and the number of currently active buses.
- The user will be prompted to select a direction.

### 2.3. Direction and Stop Data
- To ensure data currency, the application prioritizes the most recent available GTFS schedule.
- After a direction is selected (for modes 1 and 2), the application fetches the full list of stops and calculates the total route distance.

### 2.4. Real-time Monitoring and Polling (Mode 2 only)
- A dual-phase polling mechanism managed API usage:
    - **Soft Polling (120 seconds):** Used when the next scheduled departure is more than 5 minutes away and no buses are near the start.
    - **Aggressive Polling (5 seconds):** Triggered when local time is within 5 minutes of departure OR a bus is detected near the origin (<5% completion).
- Monitoring ends when a bus is confirmed to have started or all tracked departures have passed.

### 2.5. Start Notification (Mode 2 only)
- Once a bus is confirmed to have started its journey (located between the first and second stops), a bilingual alert is printed to the console.

### 2.6. User Interface and Data Display
- **Internal Logging**: All operational logs and process indicators follow a standardized format: `[STEP] - Description` in English.
- **API Transparency**: Every network request is logged with `[API] - Executing call to: <URL>` indented under its corresponding step.
- **User Prompts**: All prompts and mode selection menus are displayed in English.
- **Data Display**: Information retrieved from the API (Route Names, Station Names, Directions) is preserved in Hebrew and rendered correctly for Right-to-Left (RTL) display.

## 3. User Flow
1. User starts the application and selects an **Operation Mode**.
2. Application prompts for Bus Provider and Line Number in English.
3. Application displays a list of directions with live bus counts (Route data in Hebrew).
4. User selects a direction.
5. **Mode 1**: App shows a detailed snapshot of all active buses (bilingual labels, Hebrew station names), then exits.
6. **Mode 2**: App fetches the upcoming schedule and begins the polling loop (Soft/Aggressive) to catch the next start.
7. **Mode 3**: App shows the next 3 scheduled departures and exits.

## 4. Technical Specifications

### 4.1. API Integration
The application uses the [Open Bus Stride API](https://open-bus-stride-api.hasadna.org.il/docs) for fetching public transportation data.

For detailed technical specifications of the endpoints, optimized search parameters, and strategies for handling data transitions, refer to the **[API_GUIDE.md](./API_GUIDE.md)**.
