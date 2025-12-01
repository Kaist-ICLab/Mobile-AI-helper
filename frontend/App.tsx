import React from 'react';
import {SafeAreaView, Button, NativeModules} from 'react-native';

const { Overlay } = NativeModules;

const App = () => {
  const onStartHelp = () => {
    Overlay?.startOverlay();
  };

  return (
    <SafeAreaView style={{flex: 1, justifyContent: 'center', alignItems: 'center'}}>
      <Button title="Start Help" onPress={onStartHelp} />
    </SafeAreaView>
  );
};

// export default App;
// import React, {useState, useEffect} from 'react';
// import {
//   SafeAreaView,
//   StyleSheet,
//   Text,
//   View,
//   TouchableOpacity,
//   Alert,
//   StatusBar,
// } from 'react-native';
// import OverlayStarter from './src/native/OverlayStarter';

// const App = (): React.JSX.Element => {
//   const [overlayPermission, setOverlayPermission] = useState(false);

//   useEffect(() => {
//     checkOverlayPermission();
//   }, []);

//   const checkOverlayPermission = async () => {
//     try {
//       const hasPermission = await OverlayStarter.checkOverlayPermission();
//       setOverlayPermission(hasPermission);
//     } catch (error) {
//       console.error('Error checking overlay permission:', error);
//     }
//   };

//   const requestOverlayPermission = async () => {
//     try {
//       await OverlayStarter.requestOverlayPermission();
//       setTimeout(checkOverlayPermission, 1000);
//     } catch (error) {
//       console.error('Error requesting overlay permission:', error);
//       Alert.alert('Error', 'Failed to request overlay permission');
//     }
//   };

//   const startOverlay = async () => {
//     try {
//       if (!overlayPermission) {
//         Alert.alert(
//           'Permission Required',
//           'Overlay permission is required to show the floating help button.',
//           [
//             {text: 'Cancel', style: 'cancel'},
//             {text: 'Grant Permission', onPress: requestOverlayPermission},
//           ]
//         );
//         return;
//       }

//       await OverlayStarter.startOverlay();
//       Alert.alert(
//         'Help Active',
//         'The floating help button is now active. You can minimize this app and tap the bubble anytime for assistance.'
//       );
//     } catch (error) {
//       console.error('Error starting overlay:', error);
//       Alert.alert('Error', 'Failed to start overlay service');
//     }
//   };

//   const stopOverlay = async () => {
//     try {
//       await OverlayStarter.stopOverlay();
//       Alert.alert('Help Stopped', 'The floating help button has been removed.');
//     } catch (error) {
//       console.error('Error stopping overlay:', error);
//     }
//   };

//   return (
//     <SafeAreaView style={styles.container}>
//       <StatusBar barStyle="dark-content" backgroundColor="#f8f9fa" />
//       <View style={styles.content}>
//         <Text style={styles.title}>Senior Helper</Text>
//         <Text style={styles.subtitle}>Your personal assistant</Text>

//         <View style={styles.infoBox}>
//           <Text style={styles.infoText}>
//             Tap the "Start Help" button to activate your floating assistant.
//             {'\n\n'}
//             A small bubble will appear on your screen. Tap it anytime you need help!
//           </Text>
//         </View>

//         {!overlayPermission && (
//           <View style={styles.warningBox}>
//             <Text style={styles.warningText}>
//               ⚠️ Overlay permission not granted
//             </Text>
//             <TouchableOpacity
//               style={styles.permissionButton}
//               onPress={requestOverlayPermission}>
//               <Text style={styles.permissionButtonText}>Grant Permission</Text>
//             </TouchableOpacity>
//           </View>
//         )}

//         <TouchableOpacity
//           style={[
//             styles.helpButton,
//             !overlayPermission && styles.helpButtonDisabled,
//           ]}
//           onPress={startOverlay}
//           disabled={!overlayPermission}>
//           <Text style={styles.helpButtonText}>Start Help</Text>
//         </TouchableOpacity>

//         <TouchableOpacity style={styles.stopButton} onPress={stopOverlay}>
//           <Text style={styles.stopButtonText}>Stop Help</Text>
//         </TouchableOpacity>

//         <View style={styles.footer}>
//           <Text style={styles.footerText}>
//             Permission Status: {overlayPermission ? '✓ Granted' : '✗ Not Granted'}
//           </Text>
//         </View>
//       </View>
//     </SafeAreaView>
//   );
// };

// const styles = StyleSheet.create({
//   container: {
//     flex: 1,
//     backgroundColor: '#f8f9fa',
//   },
//   content: {
//     flex: 1,
//     padding: 24,
//     justifyContent: 'center',
//     alignItems: 'center',
//   },
//   title: {
//     fontSize: 36,
//     fontWeight: 'bold',
//     color: '#2c3e50',
//     marginBottom: 8,
//   },
//   subtitle: {
//     fontSize: 18,
//     color: '#7f8c8d',
//     marginBottom: 32,
//   },
//   infoBox: {
//     backgroundColor: '#e3f2fd',
//     padding: 20,
//     borderRadius: 12,
//     marginBottom: 24,
//     width: '100%',
//   },
//   infoText: {
//     fontSize: 16,
//     color: '#1976d2',
//     textAlign: 'center',
//     lineHeight: 24,
//   },
//   warningBox: {
//     backgroundColor: '#fff3cd',
//     padding: 16,
//     borderRadius: 12,
//     marginBottom: 24,
//     width: '100%',
//     borderWidth: 1,
//     borderColor: '#ffc107',
//   },
//   warningText: {
//     fontSize: 16,
//     color: '#856404',
//     textAlign: 'center',
//     marginBottom: 12,
//     fontWeight: '600',
//   },
//   permissionButton: {
//     backgroundColor: '#ffc107',
//     padding: 12,
//     borderRadius: 8,
//     alignItems: 'center',
//   },
//   permissionButtonText: {
//     color: '#000',
//     fontSize: 16,
//     fontWeight: '600',
//   },
//   helpButton: {
//     backgroundColor: '#4CAF50',
//     paddingVertical: 20,
//     paddingHorizontal: 60,
//     borderRadius: 16,
//     elevation: 4,
//     shadowColor: '#000',
//     shadowOffset: {width: 0, height: 2},
//     shadowOpacity: 0.25,
//     shadowRadius: 3.84,
//     marginBottom: 16,
//   },
//   helpButtonDisabled: {
//     backgroundColor: '#bdbdbd',
//   },
//   helpButtonText: {
//     color: '#fff',
//     fontSize: 24,
//     fontWeight: 'bold',
//   },
//   stopButton: {
//     backgroundColor: '#f44336',
//     paddingVertical: 16,
//     paddingHorizontal: 40,
//     borderRadius: 12,
//     elevation: 2,
//     shadowColor: '#000',
//     shadowOffset: {width: 0, height: 1},
//     shadowOpacity: 0.20,
//     shadowRadius: 1.41,
//   },
//   stopButtonText: {
//     color: '#fff',
//     fontSize: 18,
//     fontWeight: '600',
//   },
//   footer: {
//     marginTop: 32,
//     padding: 16,
//     backgroundColor: '#fff',
//     borderRadius: 8,
//     width: '100%',
//   },
//   footerText: {
//     fontSize: 14,
//     color: '#666',
//     textAlign: 'center',
//   },
// });

// export default App;
