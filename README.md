# Kav Monitor IL

A Python-based command-line application to track real-time bus locations and schedules in Israel using the Open Bus Stride API.

## Features

- **Live Tracking**: See all active buses on a specific route with their completion percentage and next stop.
- **Start Monitoring**: Polling mechanism to detect when the next bus starts its journey from the origin terminal.
- **Schedule Viewer**: View upcoming scheduled departures for any bus line.
- **Bilingual Support**: Correctly renders Hebrew names for routes and stations in the terminal.

## Project Structure

- `src/`: Source code of the application.
  - `main.py`: Entry point.
  - `kavmonitor/`: Core package.
- `docs/`: Technical specifications and API guides.
- `tests/`: Placeholder for future tests.

## Installation

1. Clone the repository.
2. Install dependencies:
   ```bash
   pip install -r requirements.txt
   ```

## Usage

Run the application from the `src` directory:
```bash
python src/main.py
```

## Data Source

This project uses data from the [Open Bus Stride API](https://open-bus-stride-api.hasadna.org.il/docs) by [The Public Knowledge Workshop (Hasadna)](https://www.hasadna.org.il/).
