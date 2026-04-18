# HelpPing 🚨

HelpPing is a personal safety and emergency location-sharing application built for Android. It allows users to quickly share their real-time location and status with emergency contacts during critical situations.

## Features ✨

- **Real-time Location Sharing**: Integration with Google Maps to share your precise coordinates.
- **Emergency Contacts**: Dedicated settings to manage and quickly alert your trusted contacts.
- **Firebase Authentication**: Secure user login and data persistence.
- **User Session Management**: Persistent login states for quick access during emergencies.
- **Safety Dashboard**: A central hub to access safety features and map tools.

## Tech Stack 🛠️

- **Language**: Java / Android SDK
- **Backend/Auth**: Firebase (Authentication, Analytics)
- **Maps**: Google Maps Platform (Maps SDK for Android)
- **UI Components**: Material Design, AndroidX fragments
- **Image Loading**: Glide

## Getting Started 🚀

To run this project locally, you'll need to set up your own Firebase project and Google Maps API Key.

### Prerequisites

- Android Studio (Ladybug or newer recommended)
- A Firebase Project
- A Google Cloud Project with Maps SDK for Android enabled

### Setup Instructions

1.  **Clone the Repository**:
    ```bash
    git clone https://github.com/yourusername/HelpPing.git
    ```

2.  **Add Firebase**:
    - Go to the [Firebase Console](https://console.firebase.google.com/).
    - Create a new project named "HelpPing".
    - Add an Android app with package name `com.example.helpping`.
    - Download `google-services.json` and place it in the `app/` directory.

3.  **Configure Google Maps API**:
    - Go to the [Google Cloud Console](https://console.cloud.google.com/).
    - Enable "Maps SDK for Android".
    - Generate an API Key.
    - Create/Open `local.properties` in the root directory and add your key:
      ```properties
      MAPS_API_KEY=YOUR_API_KEY_HERE
      ```

4.  **Build and Run**:
    - Open the project in Android Studio.
    - Sync Gradle files.
    - Run the application on an emulator or physical device.

## Security 🛡️

Sensitive information like the Maps API key and `google-services.json` are excluded from this repository for security. Ensure you follow the setup instructions above to provide your own credentials.

## License 📄

This project is licensed under the MIT License - see the [LICENSE](LICENSE) file for details.
