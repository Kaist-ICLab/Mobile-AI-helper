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