import React from 'react';
import { AppRegistry } from 'react-native';
import App from './App';
import { name as appName, apiBaseUrl } from './app.json';
import { ApiStockRepository, demoRepository } from './src/data/repository';

const repository = apiBaseUrl
  ? new ApiStockRepository(apiBaseUrl)
  : demoRepository;
function SmartStockRoot() {
  return React.createElement(App, { repository });
}
AppRegistry.registerComponent(appName, () => SmartStockRoot);
