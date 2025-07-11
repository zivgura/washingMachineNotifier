const admin = require('firebase-admin');
const path = require('path');

// Initialize Firebase Admin SDK
let firebaseApp;
let isFirebaseConfigured = false;

try {
  // Try to use service account key file
  const serviceAccountPath = path.join(__dirname, '../../serviceAccountKey.json');
  const serviceAccount = require(serviceAccountPath);
  
  firebaseApp = admin.initializeApp({
    credential: admin.credential.cert(serviceAccount),
    databaseURL: `https://${serviceAccount.project_id}.firebaseio.com`
  });
  
  console.log('✅ Firebase Admin SDK initialized with service account');
  isFirebaseConfigured = true;
} catch (error) {
  console.warn('⚠️ Service account key not found, checking environment variables');
  
  // Check if required environment variables are set
  const requiredEnvVars = [
    'FIREBASE_PROJECT_ID',
    'FIREBASE_PRIVATE_KEY',
    'FIREBASE_CLIENT_EMAIL'
  ];
  
  const missingVars = requiredEnvVars.filter(varName => !process.env[varName]);
  
  if (missingVars.length > 0) {
    console.warn(`⚠️ Missing Firebase environment variables: ${missingVars.join(', ')}`);
    console.warn('📝 Please set up Firebase configuration or add serviceAccountKey.json');
    console.warn('📖 See SETUP_INSTRUCTIONS.md for Firebase setup guide');
    
    // Create a mock Firebase app for development
    firebaseApp = {
      firestore: () => ({
        collection: () => ({
          add: async () => ({ id: 'mock-id' }),
          orderBy: () => ({
            limit: () => ({
              get: async () => ({ forEach: () => {} })
            })
          })
        })
      }),
      messaging: () => ({
        send: async () => ({ messageId: 'mock-message-id' }),
        sendMulticast: async () => ({ successCount: 0, failureCount: 0, responses: [] })
      })
    };
    
    console.log('🔧 Using mock Firebase for development');
  } else {
    // Fallback to environment variables
    firebaseApp = admin.initializeApp({
      credential: admin.credential.cert({
        projectId: process.env.FIREBASE_PROJECT_ID,
        privateKeyId: process.env.FIREBASE_PRIVATE_KEY_ID,
        privateKey: process.env.FIREBASE_PRIVATE_KEY?.replace(/\\n/g, '\n'),
        clientEmail: process.env.FIREBASE_CLIENT_EMAIL,
        clientId: process.env.FIREBASE_CLIENT_ID,
        authUri: process.env.FIREBASE_AUTH_URI,
        tokenUri: process.env.FIREBASE_TOKEN_URI,
        authProviderX509CertUrl: process.env.FIREBASE_AUTH_PROVIDER_X509_CERT_URL,
        clientX509CertUrl: process.env.FIREBASE_CLIENT_X509_CERT_URL
      }),
      databaseURL: `https://${process.env.FIREBASE_PROJECT_ID}.firebaseio.com`
    });
    
    console.log('✅ Firebase Admin SDK initialized with environment variables');
    isFirebaseConfigured = true;
  }
}

// Get Firestore instance
const db = firebaseApp.firestore();

// Get FCM instance
const messaging = firebaseApp.messaging();

// Device token management
const deviceTokens = new Set();

// Add device token
const addDeviceToken = (token) => {
  deviceTokens.add(token);
  console.log(`📱 Device token added: ${token.substring(0, 20)}...`);
  return deviceTokens.size;
};

// Remove device token
const removeDeviceToken = (token) => {
  deviceTokens.delete(token);
  console.log(`📱 Device token removed: ${token.substring(0, 20)}...`);
  return deviceTokens.size;
};

// Get all device tokens
const getDeviceTokens = () => {
  return Array.from(deviceTokens);
};

// Get device count
const getDeviceCount = () => {
  return deviceTokens.size;
};

// Send notification to all devices
const sendNotificationToAll = async (title, body, data = {}) => {
  if (!isFirebaseConfigured) {
    console.warn('⚠️ Firebase not configured, skipping notification');
    return { successCount: 0, failureCount: 0, responses: [] };
  }

  if (deviceTokens.size === 0) {
    throw new Error('No devices registered');
  }

  const message = {
    notification: {
      title,
      body
    },
    data: {
      ...data,
      timestamp: Date.now().toString()
    },
    tokens: getDeviceTokens()
  };

  try {
    const response = await messaging.sendMulticast(message);
    console.log(`📤 Notification sent to ${response.successCount}/${deviceTokens.size} devices`);
    
    // Remove failed tokens
    if (response.failureCount > 0) {
      response.responses.forEach((resp, idx) => {
        if (!resp.success) {
          const failedToken = getDeviceTokens()[idx];
          removeDeviceToken(failedToken);
          console.log(`❌ Failed to send to token: ${failedToken.substring(0, 20)}...`);
        }
      });
    }
    
    return response;
  } catch (error) {
    console.error('❌ Error sending notification:', error);
    throw error;
  }
};

// Send notification to specific device
const sendNotificationToDevice = async (token, title, body, data = {}) => {
  if (!isFirebaseConfigured) {
    console.warn('⚠️ Firebase not configured, skipping notification');
    return { messageId: 'mock-message-id' };
  }

  const message = {
    notification: {
      title,
      body
    },
    data: {
      ...data,
      timestamp: Date.now().toString()
    },
    token
  };

  try {
    const response = await messaging.send(message);
    console.log(`📤 Notification sent to device: ${token.substring(0, 20)}...`);
    return response;
  } catch (error) {
    console.error('❌ Error sending notification to device:', error);
    throw error;
  }
};

// Store notification in Firestore
const storeNotification = async (notification) => {
  if (!isFirebaseConfigured) {
    console.warn('⚠️ Firebase not configured, skipping database storage');
    return 'mock-notification-id';
  }

  try {
    const docRef = await db.collection('notifications').add({
      ...notification,
      timestamp: admin.firestore.FieldValue.serverTimestamp()
    });
    console.log(`💾 Notification stored with ID: ${docRef.id}`);
    return docRef.id;
  } catch (error) {
    console.error('❌ Error storing notification:', error);
    throw error;
  }
};

// Get recent notifications
const getRecentNotifications = async (limit = 50) => {
  if (!isFirebaseConfigured) {
    console.warn('⚠️ Firebase not configured, returning empty notification history');
    return [];
  }

  try {
    const snapshot = await db.collection('notifications')
      .orderBy('timestamp', 'desc')
      .limit(limit)
      .get();
    
    const notifications = [];
    snapshot.forEach(doc => {
      notifications.push({
        id: doc.id,
        ...doc.data()
      });
    });
    
    return notifications;
  } catch (error) {
    console.error('❌ Error getting notifications:', error);
    throw error;
  }
};

module.exports = {
  db,
  messaging,
  addDeviceToken,
  removeDeviceToken,
  getDeviceTokens,
  getDeviceCount,
  sendNotificationToAll,
  sendNotificationToDevice,
  storeNotification,
  getRecentNotifications,
  isFirebaseConfigured
}; 