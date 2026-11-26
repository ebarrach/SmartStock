import React from 'react';
import { createRoot } from 'react-dom/client';
import App from './App';
import { ApiStockRepository, demoRepository } from './src/data/repository';
import config from './app.json';
const repository = config.apiBaseUrl
  ? new ApiStockRepository(config.apiBaseUrl)
  : demoRepository;
createRoot(document.getElementById('root')!).render(
  <App repository={repository} />,
);
