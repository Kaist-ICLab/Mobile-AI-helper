import {NativeModules} from 'react-native';

interface OverlayModule {
  startOverlay(): Promise<void>;
  stopOverlay(): Promise<void>;
  checkOverlayPermission(): Promise<boolean>;
  requestOverlayPermission(): Promise<void>;
}

const {OverlayModule} = NativeModules;

export default OverlayModule as OverlayModule;
