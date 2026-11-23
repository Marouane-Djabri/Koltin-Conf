// three.js: resolve `require('three')` to its ES module build (three.module.js), the same file
// GLTFLoader imports. The CommonJS build needs Node's `process` and would load a second copy.
config.resolve = config.resolve || {};
config.resolve.conditionNames = ['import', 'module', 'browser', 'default'];
