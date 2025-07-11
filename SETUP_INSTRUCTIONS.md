# Washing Machine Notification System - Setup Instructions

## Prerequisites

Before you begin, make sure you have the following installed:

### Required Software
- **Android Studio** (latest version) - [Download here](https://developer.android.com/studio)
- **Node.js** (version 16 or higher) - [Download here](https://nodejs.org/)
- **Git** - [Download here](https://git-scm.com/)
- **Java Development Kit (JDK)** - [Download here](https://adoptium.net/)

### Required Accounts
- **Google Account** (for Firebase)
- **GitHub Account** (optional, for version control)

## Step 1: Development Environment Setup

### 1.1 Install Required Software

#### Android Studio
1. Download Android Studio from the official website
2. Run the installer and follow the setup wizard
3. During installation, make sure to install:
   - Android SDK
   - Android SDK Platform-Tools
   - Android Virtual Device (AVD)
4. Launch Android Studio and complete the initial setup

#### Node.js
1. Download Node.js from nodejs.org
2. Run the installer
3. Verify installation by opening terminal/command prompt:
   ```bash
   node --version
   npm --version
   ```

#### Git
1. Download Git from git-scm.com
2. Install with default settings
3. Verify installation:
   ```bash
   git --version
   ```

### 1.2 Configure Development Environment

#### Android Studio Configuration
1. Open Android Studio
2. Go to File → Settings (Windows/Linux) or Android Studio → Preferences (Mac)
3. Navigate to Appearance & Behavior → System Settings → Android SDK
4. Make sure you have Android SDK Platform 23 or higher installed
5. Go to Plugins and install:
   - Kotlin plugin (should be pre-installed)
   - Firebase plugin (optional)

#### VS Code Configuration (Optional)
1. Install VS Code from [code.visualstudio.com](https://code.visualstudio.com/)
2. Install these extensions:
   - React Developer Tools
   - Node.js Extension Pack
   - GitLens
   - Prettier

## Step 2: Firebase Project Setup

### 2.1 Create Firebase Project
1. Go to [Firebase Console](https://console.firebase.google.com/)
2. Click "Create a project"
3. Enter project name: `WashingMachineNotifier`
4. Enable Google Analytics (optional)
5. Click "Create project"

### 2.2 Enable Required Services
1. In your Firebase project, go to "Build" section
2. Click "Cloud Messaging" and enable it
3. Go to "Firestore Database" and create a database
4. Choose "Start in test mode" for now

### 2.3 Add Android App to Firebase
1. In Firebase console, click "Add app" → Android
2. Enter package name: `com.yourname.washingmachine`
3. Enter app nickname: "Washing Machine Notifier"
4. Click "Register app"
5. Download the `google-services.json` file
6. Place this file in the `android-app/app/` directory (we'll create this structure)

### 2.4 Generate Service Account Key
1. In Firebase console, go to Project Settings
2. Click "Service accounts" tab
3. Click "Generate new private key"
4. Download the JSON file
5. Rename it to `serviceAccountKey.json`
6. Place it in the `server/` directory (we'll create this structure)

## Step 3: Project Structure Setup

### 3.1 Clone/Create Project
```bash
# If you have a Git repository
git clone <your-repo-url>
cd washing-machine-notifier

# Or create new directory
mkdir washing-machine-notifier
cd washing-machine-notifier
```

### 3.2 Create Project Structure
The project structure will be created automatically when we implement each component. For now, ensure you have:

```
washing-machine-notifier/
├── android-app/          # Will be created
├── web-dashboard/        # Will be created  
├── server/              # Will be created
├── docs/                # Will be created
├── scripts/             # Will be created
├── PROJECT_PLAN.md      # Already exists
├── SETUP_INSTRUCTIONS.md # This file
└── README.md            # Will be created
```

**Note:**
- To initialize the Android app, open the `android-app` directory in Android Studio and create a new project there (with the correct package name: `com.yourname.washingmachine`).
- The React web dashboard is scaffolded and ready for development.

## Step 4: Android App Setup

### 4.1 Create Android Project
1. Open Android Studio
2. Click "New Project"
3. Select "Empty Activity"
4. Configure project:
   - Name: `WashingMachineNotifier`
   - Package name: `com.yourname.washingmachine`
   - Language: Kotlin
   - Minimum SDK: API 23 (Android 6.0)
5. Click "Finish"

### 4.2 Add Firebase Configuration
1. Place `google-services.json` in the `app/` directory
2. Add Firebase dependencies to `build.gradle` files
3. Sync project with Gradle files

### 4.3 Configure Permissions
Add required permissions to `AndroidManifest.xml`:
- `RECORD_AUDIO`
- `INTERNET`
- `WAKE_LOCK`
- `FOREGROUND_SERVICE`
- `POST_NOTIFICATIONS`

## Step 5: Node.js Server Setup

### 5.1 Create Server Directory
```bash
mkdir server
cd server
npm init -y
```

### 5.2 Install Dependencies
```bash
npm install express cors firebase-admin socket.io
npm install --save-dev nodemon
```

### 5.3 Configure Firebase Admin
1. Place `serviceAccountKey.json` in the server directory
2. Configure Firebase Admin SDK in your server code

## Step 6: React Web Dashboard Setup

### 6.1 Create React App
```bash
npx create-react-app web-dashboard
cd web-dashboard
```

### 6.2 Install Dependencies
```bash
npm install @mui/material @emotion/react @emotion/styled
npm install socket.io-client axios react-router-dom
```

## Step 7: Testing Your Setup

### 7.1 Test Android Studio
1. Open Android Studio
2. Create a simple "Hello World" app
3. Run it on an emulator or device
4. Verify everything works

### 7.2 Test Node.js
```bash
cd server
node -e "console.log('Node.js is working!')"
```

### 7.3 Test React
```bash
cd web-dashboard
npm start
```
This should open your browser to `http://localhost:3000`

## Step 8: Development Workflow

### 8.1 Running the Complete System
1. **Start the server:**
   ```bash
   cd server
   npm start
   ```

2. **Start the web dashboard:**
   ```bash
   cd web-dashboard
   npm start
   ```

3. **Run the Android app:**
   - Open Android Studio
   - Open the android-app project
   - Click "Run" (green play button)

### 8.2 Development Tips
- Use separate terminal windows for server and web dashboard
- Keep Android Studio open for Android development
- Use VS Code for web development (optional)
- Test each component individually before integration

## Troubleshooting

### Common Issues

#### Android Studio Issues
- **SDK not found**: Install Android SDK through SDK Manager
- **Gradle sync failed**: Check internet connection and try again
- **Device not detected**: Enable USB debugging on your phone

#### Node.js Issues
- **Permission denied**: Use `sudo` (Mac/Linux) or run as administrator (Windows)
- **Port already in use**: Change port in server configuration
- **Module not found**: Run `npm install` in the correct directory

#### React Issues
- **Port 3000 in use**: React will automatically suggest another port
- **Build failed**: Check for syntax errors in your code
- **Dependencies missing**: Run `npm install`

#### Firebase Issues
- **Authentication failed**: Check your service account key
- **Project not found**: Verify your Firebase project ID
- **Permission denied**: Check Firebase security rules

### Getting Help
1. Check the console/terminal for error messages
2. Search for error messages online
3. Check the troubleshooting section in our documentation
4. Create an issue in the project repository

## Next Steps

After completing this setup:

1. **Follow the implementation plan** in `PROJECT_PLAN.md`
2. **Start with Phase 1.2**: Firebase Project Setup
3. **Implement each component** step by step
4. **Test frequently** as you build
5. **Document any issues** you encounter

## Support

If you encounter issues:
1. Check the troubleshooting section above
2. Search online for specific error messages
3. Check the project documentation
4. Create an issue in the project repository

---

**Note**: This setup guide assumes you're following the implementation plan. Each step corresponds to a phase in the project plan. 