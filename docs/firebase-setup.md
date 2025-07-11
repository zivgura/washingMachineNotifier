# Firebase Setup Guide

## Overview
This guide will help you set up Firebase for the Washing Machine Notification System.

## Step 1: Create Firebase Project

### 1.1 Access Firebase Console
1. Go to [Firebase Console](https://console.firebase.google.com/)
2. Sign in with your Google account
3. Click "Create a project"

### 1.2 Configure Project
1. **Project name**: `Washing-machine-notifier`
2. **Project ID**: Will be auto-generated (e.g., `washingmachinenotifier-12345`)
3. **Google Analytics**: Enable (optional but recommended)
4. Click "Create project"

### 1.3 Project Settings
1. Wait for project creation to complete
2. Click "Continue" to enter the project
3. Note your **Project ID** (you'll need this later)

## Step 2: Enable Required Services

### 2.1 Cloud Messaging (FCM)
1. In the left sidebar, click "Build" → "Cloud Messaging"
2. Click "Enable Cloud Messaging"
3. Note your **Server key** (you'll need this for the server)

### 2.2 Firestore Database
1. In the left sidebar, click "Build" → "Firestore Database"
2. Click "Create database"
3. Choose "Start in test mode" (we'll secure it later)
4. Select a location close to you
5. Click "Done"

### 2.3 Authentication (Optional)
1. In the left sidebar, click "Build" → "Authentication"
2. Click "Get started"
3. Go to "Sign-in method" tab
4. Enable "Email/Password" if you want user accounts

## Step 3: Add Android App

### 3.1 Register Android App
1. In the project overview, click "Add app" (</> icon)
2. Select the Android icon
3. Enter app details:
   - **Android package name**: `com.yourname.washingmachine`
   - **App nickname**: `Washing Machine Notifier`
   - **Debug signing certificate SHA-1**: (leave blank for now)
4. Click "Register app"

### 3.2 Download Configuration File
1. Download the `google-services.json` file
2. Place it in the `android-app/app/` directory
3. **Important**: Never commit this file to public repositories

### 3.3 Complete Setup
1. Click "Next" through the remaining steps
2. You can skip the "Add Firebase SDK" step for now
3. Click "Continue to console"

## Step 4: Generate Service Account Key

### 4.1 Access Service Accounts
1. In the left sidebar, click the gear icon (⚙️) next to "Project Overview"
2. Click "Project settings"
3. Go to the "Service accounts" tab

### 4.2 Generate Private Key
1. Click "Generate new private key"
2. Click "Generate key"
3. Download the JSON file
4. Rename it to `serviceAccountKey.json`
5. Place it in the `server/` directory
6. **Important**: Never commit this file to public repositories

## Step 5: Configure Security Rules

### 5.1 Firestore Security Rules
1. In Firestore Database, click "Rules" tab
2. Replace the rules with:

```javascript
rules_version = '2';
service cloud.firestore {
  match /databases/{database}/documents {
    // Allow read/write access to authenticated users
    match /{document=**} {
      allow read, write: if request.auth != null;
    }
    
    // Allow device tokens to be stored
    match /devices/{deviceId} {
      allow read, write: if true; // We'll secure this later
    }
    
    // Allow notifications to be stored
    match /notifications/{notificationId} {
      allow read, write: if true; // We'll secure this later
    }
  }
}
```

3. Click "Publish"

## Step 6: Test Configuration

### 6.1 Verify Project Setup
1. Go to Project Overview
2. Verify you see:
   - ✅ Cloud Messaging enabled
   - ✅ Firestore Database created
   - ✅ Android app registered

### 6.2 Test FCM
1. In Cloud Messaging, click "Send your first message"
2. Fill in the form:
   - **Notification title**: `Test Notification`
   - **Notification text**: `This is a test message`
3. Click "Send message"
4. You should see "Message sent successfully"

## Step 7: Environment Variables

### 7.1 Create Environment File
Create a file called `.env` in the server directory:

```bash
# Firebase Configuration
FIREBASE_PROJECT_ID=your-project-id
FIREBASE_PRIVATE_KEY_ID=your-private-key-id
FIREBASE_PRIVATE_KEY="-----BEGIN PRIVATE KEY-----\nYour private key here\n-----END PRIVATE KEY-----\n"
FIREBASE_CLIENT_EMAIL=your-service-account-email
FIREBASE_CLIENT_ID=your-client-id
FIREBASE_AUTH_URI=https://accounts.google.com/o/oauth2/auth
FIREBASE_TOKEN_URI=https://oauth2.googleapis.com/token
FIREBASE_AUTH_PROVIDER_X509_CERT_URL=https://www.googleapis.com/oauth2/v1/certs
FIREBASE_CLIENT_X509_CERT_URL=your-cert-url

# Server Configuration
PORT=3000
NODE_ENV=development
```

### 7.2 Update .gitignore
Add these lines to your `.gitignore` file:

```gitignore
# Firebase
google-services.json
serviceAccountKey.json
.env

# Dependencies
node_modules/
.gradle/
build/

# IDE
.idea/
.vscode/
*.iml

# OS
.DS_Store
Thumbs.db
```

## Troubleshooting

### Common Issues

#### "Project not found" Error
- Verify your project ID is correct
- Check that you're using the right service account key
- Ensure the service account has the necessary permissions

#### "Permission denied" Error
- Check your Firestore security rules
- Verify your service account has the right roles
- Make sure you're using the correct project

#### FCM Not Working
- Verify your Server key is correct
- Check that the device token is valid
- Ensure the app has the right permissions

### Getting Help
1. Check the Firebase console for error messages
2. Review the Firebase documentation
3. Check the troubleshooting section in our main documentation

## Next Steps

After completing this setup:

1. **Update the project plan** to mark Phase 1.2 as complete
2. **Begin Phase 1.3**: Project Structure
3. **Start implementing** the Android app
4. **Test the configuration** with a simple FCM message

## Security Notes

⚠️ **Important Security Considerations:**

1. **Never commit sensitive files**:
   - `google-services.json`
   - `serviceAccountKey.json`
   - `.env` files

2. **Use environment variables** for sensitive data

3. **Secure your Firestore rules** before production

4. **Monitor your Firebase usage** to stay within free tier limits

5. **Regularly rotate service account keys**

---

**Firebase setup complete! 🎉**

You can now proceed to Phase 1.3: Project Structure. 