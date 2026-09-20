# TomTom Bandit Camera Protocol Reference (v2)

This document describes the communication protocol for the TomTom Bandit action camera as implemented in Bandit Viewer.

## Network Configuration
- **Camera IP**: Always `192.168.1.101`.
- **Phone IP**: Typically `192.168.1.x` (assigned by the camera).
- **Transport**: The phone must be joined to the camera's Wi-Fi network.

## REST API (Control)
Base URL: `http://192.168.1.101/api`

### API Version
- **Request**: `GET /api/version`
- **Response**: `{"version": "2"}`

### Camera Status
- **Request**: `GET /api/2/status`
- **Response**: JSON object containing:
  - `battery_level_pct` (int)
  - `battery_charging` (bool)
  - `viewfinder_active` (bool)
  - `recording_active` (bool)
  - `viewfinder_streaming_port` (int)

### Start Viewfinder
- **Request**: `POST /api/2/viewfinder`
- **Headers**: `Content-Type: application/json`, `Connection: close`
- **Body**: `{"viewfinder_active":true,"viewfinder_streaming_port":4001}`
- **Response**: HTTP 200

### Stop Viewfinder
- **Request**: `POST /api/2/viewfinder`
- **Body**: `{"viewfinder_active":false,"viewfinder_streaming_port":-1}`
- **Response**: HTTP 200

## UDP Video Stream
The camera sends 768x432 JPEG frames at up to ~30 fps to the requesting device on port 4001.

### Datagram Format (Big-Endian)
Each datagram contains a 7-byte header followed by the payload.

| Offset | Size | Type | Description |
| :--- | :--- | :--- | :--- |
| 0 | 2 | u16 | Sync = `0x55AA` |
| 2 | 1 | u8 | Message Type: `0` = START, `1` = DATA |
| 3 | 2 | u16 | Packet Number (wraps at 65535) |
| 5 | 2 | u16 | Payload Length |
| 7 | ... | ... | Payload |

### START Message (Type 0)
- **Payload (8 bytes)**:
  - `u32` Image Length (total bytes for the frame)
  - `float32` Presentation Timestamp (PTS) in seconds
- **Action**: Resets the frame buffer and sets the expected next packet number to `(pnum + 1) % 65536`.

### DATA Message (Type 1)
- **Payload**: Chunk of the JPEG image.
- **Action**: Appends to the frame buffer if the packet number matches the expected sequence. If a packet is lost, the current frame is abandoned.

### Frame Completion
A frame is complete when the accumulated bytes reach the `image_length` specified in the START message. A valid JPEG frame starts with `FF D8` and ends with `FF D9`.
