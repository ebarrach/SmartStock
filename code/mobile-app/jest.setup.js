/* global jest, beforeEach */
jest.mock('@react-native-async-storage/async-storage', () =>
  require('@react-native-async-storage/async-storage/jest'),
);
beforeEach(async () => {
  const {
    createAsyncStorage,
  } = require('@react-native-async-storage/async-storage');
  await createAsyncStorage('smartstock-connection').clear();
});
