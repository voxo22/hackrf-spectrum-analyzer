package jspectrumanalyzer.iq;

import java.util.Locale;

/** Names used by the passive Bluetooth identity decoders. Unknown values remain visible as hexadecimal. */
final class BluetoothAssignedNumbers {
	private BluetoothAssignedNumbers() {}

	/** Bluetooth Core version values shared by LL_VERSION_IND and LMP_version_res. */
	static String bluetoothVersion(int value) {
		switch(value) {
		case 0x00:return "Bluetooth 1.0b";case 0x01:return "Bluetooth 1.1";
		case 0x02:return "Bluetooth 1.2";case 0x03:return "Bluetooth 2.0 + EDR";
		case 0x04:return "Bluetooth 2.1 + EDR";case 0x05:return "Bluetooth 3.0 + HS";
		case 0x06:return "Bluetooth 4.0";case 0x07:return "Bluetooth 4.1";
		case 0x08:return "Bluetooth 4.2";case 0x09:return "Bluetooth 5.0";
		case 0x0a:return "Bluetooth 5.1";case 0x0b:return "Bluetooth 5.2";
		case 0x0c:return "Bluetooth 5.3";case 0x0d:return "Bluetooth 5.4";
		case 0x0e:return "Bluetooth 6.0";case 0x0f:return "Bluetooth 6.1";
		default:return String.format(Locale.US,"Bluetooth version 0x%02X",value);
		}
	}

	static String company(int id) {
		switch(id) {
		case 0x0000:return "Ericsson Technology Licensing";
		case 0x0001:return "Nokia Mobile Phones";
		case 0x0002:return "Intel Corp.";
		case 0x0006:return "Microsoft";
		case 0x000a:return "Cambridge Silicon Radio";
		case 0x000d:return "Texas Instruments";
		case 0x000f:return "Broadcom";
		case 0x001d:return "Qualcomm";
		case 0x0025:return "NXP Semiconductors";
		case 0x0030:return "STMicroelectronics";
		case 0x004c:return "Apple, Inc.";
		case 0x0059:return "Nordic Semiconductor";
		case 0x0075:return "Samsung Electronics";
		case 0x00e0:return "Google";
		case 0x0131:return "Cypress Semiconductor";
		case 0x02e5:return "Espressif Systems";
		default:return String.format(Locale.US,"Company ID 0x%04X",id);
		}
	}

	static String service(int uuid) {
		switch(uuid) {
		case 0x1800:return "Generic Access";case 0x1801:return "Generic Attribute";
		case 0x1802:return "Immediate Alert";case 0x1803:return "Link Loss";
		case 0x1804:return "Tx Power";case 0x1805:return "Current Time";
		case 0x1808:return "Glucose";case 0x1809:return "Health Thermometer";
		case 0x180a:return "Device Information";case 0x180d:return "Heart Rate";
		case 0x180e:return "Phone Alert Status";case 0x180f:return "Battery";
		case 0x1810:return "Blood Pressure";case 0x1811:return "Alert Notification";
		case 0x1812:return "Human Interface Device";case 0x1813:return "Scan Parameters";
		case 0x1814:return "Running Speed/Cadence";case 0x1816:return "Cycling Speed/Cadence";
		case 0x1818:return "Cycling Power";case 0x1819:return "Location and Navigation";
		case 0x181a:return "Environmental Sensing";case 0x181c:return "User Data";
		case 0x181d:return "Weight Scale";case 0x181f:return "Continuous Glucose Monitoring";
		case 0x1822:return "Pulse Oximeter";case 0x1826:return "Fitness Machine";
		case 0x1827:return "Mesh Provisioning";case 0x1828:return "Mesh Proxy";
		case 0x1843:return "Audio Input Control";case 0x1844:return "Volume Control";
		case 0x1845:return "Volume Offset Control";case 0x1846:return "Coordinated Set Identification";
		case 0x1848:return "Media Control";case 0x1849:return "Generic Media Control";
		case 0x184b:return "Telephone Bearer";case 0x184c:return "Generic Telephone Bearer";
		case 0x184d:return "Microphone Control";case 0x184e:return "Audio Stream Control";
		case 0x184f:return "Broadcast Audio Scan";case 0x1850:return "Published Audio Capabilities";
		case 0x1851:return "Basic Audio Announcement";case 0x1852:return "Broadcast Audio Announcement";
		case 0xfeaa:return "Eddystone";case 0xfd6f:return "Exposure Notification";
		default:return String.format(Locale.US,"UUID 0x%04X",uuid);
		}
	}

	static String characteristic(int uuid) {
		switch(uuid) {
		case 0x2a00:return "Device Name";case 0x2a01:return "Appearance";
		case 0x2a19:return "Battery Level";case 0x2a23:return "System ID";
		case 0x2a24:return "Model Number";case 0x2a25:return "Serial Number";
		case 0x2a26:return "Firmware Revision";case 0x2a27:return "Hardware Revision";
		case 0x2a28:return "Software Revision";case 0x2a29:return "Manufacturer Name";
		case 0x2a4a:return "HID Information";case 0x2a4b:return "Report Map";
		case 0x2a4d:return "Report";case 0x2a50:return "PnP ID";
		default:return String.format(Locale.US,"UUID 0x%04X",uuid);
		}
	}

	static String deviceTypeForService(int uuid) {
		switch(uuid) {
		case 0x1812:return "Human interface / remote";case 0x1808:return "Glucose meter";
		case 0x1809:return "Thermometer";case 0x180d:return "Heart-rate sensor";
		case 0x1810:return "Blood-pressure device";case 0x1814:return "Running/walking sensor";
		case 0x1816:case 0x1818:return "Cycling sensor";case 0x181a:return "Environmental sensor";
		case 0x181d:return "Weight scale";case 0x1822:return "Pulse oximeter";
		case 0x1826:return "Fitness machine";case 0xfeaa:return "Eddystone beacon";
		case 0xfd6f:return "Exposure notification";
		case 0x1843:case 0x1844:case 0x1845:case 0x1846:case 0x1848:case 0x1849:
		case 0x184b:case 0x184c:case 0x184d:case 0x184e:case 0x184f:case 0x1850:
		case 0x1851:case 0x1852:return "LE Audio device";
		default:return "--";
		}
	}

	static String appearanceType(int value) {
		switch(value) {
		case 0x0180:return "Remote control";case 0x0280:return "Media player";
		case 0x03c1:return "Keyboard";case 0x03c2:return "Mouse";case 0x03c3:return "Joystick";
		case 0x03c4:return "Gamepad";case 0x0841:return "Standalone speaker";case 0x0842:return "Soundbar";
		case 0x0845:return "Speakerphone";case 0x0881:return "Microphone";case 0x0941:return "Earbud";
		case 0x0942:return "Headset";case 0x0943:return "Headphones";case 0x09c1:return "Amplifier";
		case 0x09c2:return "AV receiver";case 0x09c3:return "Radio";case 0x09ca:return "Set-top box";
		case 0x0a01:return "Television";case 0x0a02:return "Monitor";case 0x0a03:return "Projector";
		default:break;
		}
		switch(value>>>6) {
		case 1:return "Phone";case 2:return "Computer";case 3:return "Watch";case 5:return "Display";
		case 6:return "Remote control";case 10:return "Media player";case 12:return "Thermometer";
		case 13:return "Heart-rate sensor";case 14:return "Blood-pressure device";
		case 15:return "Human interface device";case 18:return "Cycling device";
		case 33:return "Audio sink";case 34:return "Audio source";case 37:return "Wearable audio device";
		case 39:return "AV equipment";case 40:return "Display equipment";case 41:return "Hearing aid";
		case 42:return "Gaming device";default:return "Bluetooth device";
		}
	}
}
