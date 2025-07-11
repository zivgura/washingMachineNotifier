const express = require('express');
const router = express.Router();
const { 
  addDeviceToken, 
  removeDeviceToken, 
  getDeviceTokens, 
  getDeviceCount 
} = require('../services/firebase');

// Register a new device
router.post('/register', async (req, res) => {
  try {
    const { token, deviceInfo } = req.body;
    
    if (!token) {
      return res.status(400).json({
        success: false,
        message: 'Device token is required'
      });
    }

    const deviceCount = addDeviceToken(token);
    
    res.json({
      success: true,
      message: 'Device registered successfully',
      deviceCount,
      token: token.substring(0, 20) + '...'
    });
  } catch (error) {
    console.error('Error registering device:', error);
    res.status(500).json({
      success: false,
      message: 'Failed to register device',
      error: error.message
    });
  }
});

// Unregister a device
router.post('/unregister', async (req, res) => {
  try {
    const { token } = req.body;
    
    if (!token) {
      return res.status(400).json({
        success: false,
        message: 'Device token is required'
      });
    }

    const deviceCount = removeDeviceToken(token);
    
    res.json({
      success: true,
      message: 'Device unregistered successfully',
      deviceCount,
      token: token.substring(0, 20) + '...'
    });
  } catch (error) {
    console.error('Error unregistering device:', error);
    res.status(500).json({
      success: false,
      message: 'Failed to unregister device',
      error: error.message
    });
  }
});

// Get all registered devices
router.get('/', async (req, res) => {
  try {
    const tokens = getDeviceTokens();
    const count = getDeviceCount();
    
    res.json({
      success: true,
      count,
      devices: tokens.map(token => ({
        token: token.substring(0, 20) + '...',
        registered: true
      }))
    });
  } catch (error) {
    console.error('Error getting devices:', error);
    res.status(500).json({
      success: false,
      message: 'Failed to get devices',
      error: error.message
    });
  }
});

// Get device count
router.get('/count', async (req, res) => {
  try {
    const count = getDeviceCount();
    
    res.json({
      success: true,
      count
    });
  } catch (error) {
    console.error('Error getting device count:', error);
    res.status(500).json({
      success: false,
      message: 'Failed to get device count',
      error: error.message
    });
  }
});

// Health check for devices
router.get('/health', async (req, res) => {
  try {
    const count = getDeviceCount();
    
    res.json({
      success: true,
      status: 'healthy',
      deviceCount: count,
      timestamp: new Date().toISOString()
    });
  } catch (error) {
    console.error('Error checking device health:', error);
    res.status(500).json({
      success: false,
      status: 'unhealthy',
      message: 'Failed to check device health',
      error: error.message
    });
  }
});

module.exports = router; 