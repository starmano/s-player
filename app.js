// Starts the S Player download site. Used by `npm start` and cPanel's "Setup Node.js App",
// which load this file with require() rather than running it directly.
'use strict';

const { createServer } = require('./website/server');

const port = Number(process.env.PORT) || 7001;

createServer().listen(port, () => {
  console.log(`S Player site running on port ${port}`);
});
