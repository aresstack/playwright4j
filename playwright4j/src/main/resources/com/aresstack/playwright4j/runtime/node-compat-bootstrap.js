(function installPlaywright4JCompatibilityLayer(global) {
  const modules = {};
  const host = global.__playwright4jHost;

  global.global = global;

  let timerSequence = 0;
  const canceledTimers = {};

  if (typeof global.setTimeout !== 'function') {
    global.setTimeout = function (callback, delay) {
      const handle = ++timerSequence;

      if (!delay || delay <= 0) {
        Promise.resolve().then(function () {
          if (!canceledTimers[handle]) {
            callback();
          }
        });
      }

      return handle;
    };
  }

  if (typeof global.clearTimeout !== 'function') {
    global.clearTimeout = function (handle) {
      canceledTimers[handle] = true;
    };
  }

  function reportMissing(name) {
    host.missingHostFunctionReporter().reportMissingHostFunction(name);
  }

  function unsupported(name) {
    return function unsupportedFunction() {
      reportMissing(name);
      throw new Error('Missing Playwright4J host implementation: ' + name);
    };
  }

  modules.fs = {
    existsSync: function (path) {
      return host.fileSystem().existsSync(String(path));
    },
    readFileSync: function (path, encoding) {
      return host.fileSystem().readFileSync(String(path), encoding || 'utf8');
    },
    writeFileSync: unsupported('fs.writeFileSync'),
    mkdirSync: unsupported('fs.mkdirSync'),
    rmSync: unsupported('fs.rmSync'),
    promises: {
      readFile: async function (path, encoding) {
        return host.fileSystem().readFileSync(String(path), encoding || 'utf8');
      },
      mkdtemp: async function (prefix) {
        return String(prefix || '/tmp/playwright4j-') + Math.floor(Math.random() * 1000000000);
      },
      writeFile: unsupported('fs.promises.writeFile'),
      mkdir: async function () {
        return undefined;
      },
      rm: async function () {
        return undefined;
      }
    }
  };

  modules.path = {
    join: function () {
      return Array.prototype.join.call(arguments, '/');
    },
    resolve: function () {
      return Array.prototype.join.call(arguments, '/');
    },
    dirname: function (value) {
      return String(value).replace(/[\\/][^\\/]*$/, '') || '.';
    },
    basename: function (value) {
      return String(value).replace(/^.*[\\/]/, '');
    },
    extname: function (value) {
      const match = String(value).match(/(\.[^./\\]+)$/);
      return match ? match[1] : '';
    },
    isAbsolute: function (value) {
      const text = String(value);
      return text.indexOf('/') === 0 || /^[A-Za-z]:[\\/]/.test(text);
    },
    normalize: function (value) {
      return normalizeResourceName(String(value));
    },
    relative: function (from, to) {
      return String(to).replace(String(from), '').replace(/^[\\/]/, '');
    },
    sep: '/'
  };

  modules.os = {
    platform: function () {
      return host.environment().platform();
    },
    arch: function () {
      return host.environment().architecture();
    },
    tmpdir: function () {
      return '/tmp';
    },
    homedir: function () {
      return '/home/playwright4j';
    },
    release: function () {
      return 'playwright4j';
    }
  };

  modules.child_process = {
    spawn: unsupported('child_process.spawn'),
    execFile: unsupported('child_process.execFile'),
    execFileSync: unsupported('child_process.execFileSync')
  };

  class EventEmitter {
    constructor() {
      this.listenersByName = {};
    }

    on(name, listener) {
      this.listenersByName[name] = this.listenersByName[name] || [];
      this.listenersByName[name].push(listener);
      return this;
    }

    once(name, listener) {
      const self = this;
      function onceListener() {
        self.off(name, onceListener);
        return listener.apply(this, arguments);
      }
      return this.on(name, onceListener);
    }

    off(name, listener) {
      const listeners = this.listenersByName[name] || [];
      this.listenersByName[name] = listeners.filter(function (candidate) {
        return candidate !== listener;
      });
      return this;
    }

    removeListener(name, listener) {
      return this.off(name, listener);
    }

    setMaxListeners(value) {
      this.maxListeners = value;
      return this;
    }

    getMaxListeners() {
      return this.maxListeners || 0;
    }

    listeners(name) {
      return (this.listenersByName[name] || []).slice();
    }

    listenerCount(name) {
      return (this.listenersByName[name] || []).length;
    }

    emit(name) {
      const args = Array.prototype.slice.call(arguments, 1);
      const listeners = this.listenersByName[name] || [];
      listeners.slice().forEach(function (listener) {
        listener.apply(null, args);
      });
      return listeners.length > 0;
    }
  }

  modules.events = EventEmitter;
  modules.events.EventEmitter = EventEmitter;

  if (typeof global.AbortController !== 'function') {
    global.AbortController = class AbortController {
      constructor() {
        this.signal = new EventEmitter();
        this.signal.aborted = false;
      }

      abort(reason) {
        this.signal.aborted = true;
        this.signal.reason = reason;
        this.signal.emit('abort');
      }
    };
  }

  modules.assert = function assert(condition, message) {
    if (!condition) {
      throw new Error(message || 'Assertion failed');
    }
  };

  modules.util = {
    inspect: function (value) {
      try {
        return JSON.stringify(value);
      } catch (error) {
        return String(value);
      }
    },
    format: function (pattern) {
      const args = Array.prototype.slice.call(arguments, 1);
      let index = 0;
      let result = String(pattern).replace(/%[sdj]/g, function (token) {
        const value = args[index++];

        if (token === '%j') {
          try {
            return JSON.stringify(value);
          } catch (error) {
            return '[Circular]';
          }
        }

        return String(value);
      });

      while (index < args.length) {
        result += ' ' + String(args[index++]);
      }

      return result;
    },
    promisify: function (fn) {
      return function () {
        const args = Array.prototype.slice.call(arguments);
        return new Promise(function (resolve, reject) {
          fn.apply(null, args.concat(function (error, result) {
            if (error) {
              reject(error);
            } else {
              resolve(result);
            }
          }));
        });
      };
    },
    deprecate: function (fn) {
      return fn;
    },
    inherits: function (constructor, superConstructor) {
      if (!superConstructor || !superConstructor.prototype) {
        superConstructor = Object;
      }

      constructor.super_ = superConstructor;
      constructor.prototype = Object.create(superConstructor.prototype, {
        constructor: {
          value: constructor,
          enumerable: false,
          writable: true,
          configurable: true
        }
      });
    }
  };

  modules.async_hooks = {
    AsyncLocalStorage: class AsyncLocalStorage {
      constructor() {
        this.store = undefined;
      }

      run(store, callback) {
        const previousStore = this.store;
        this.store = store;

        try {
          return callback();
        } finally {
          this.store = previousStore;
        }
      }

      getStore() {
        return this.store;
      }

      enterWith(store) {
        this.store = store;
      }

      disable() {
        this.store = undefined;
      }
    }
  };

  modules.constants = {
    O_RDONLY: 0,
    O_WRONLY: 1,
    O_RDWR: 2,
    SIGHUP: 1,
    SIGINT: 2,
    SIGTERM: 15
  };

  modules.crypto = {
    randomBytes: function (size) {
      const bytes = new Uint8Array(size);

      for (let index = 0; index < size; index++) {
        bytes[index] = Math.floor(Math.random() * 256);
      }

      return bytes;
    },
    createHash: unsupported('crypto.createHash')
  };

  modules.zlib = {
    gzipSync: function (value) {
      return value;
    },
    gunzipSync: function (value) {
      return value;
    }
  };

  modules.stream = {
    Stream: class Stream extends EventEmitter {},
    Readable: class Readable extends EventEmitter {},
    Writable: class Writable extends EventEmitter {},
    Transform: class Transform extends EventEmitter {
      pipe(destination) {
        return destination;
      }
    },
    PassThrough: class PassThrough extends EventEmitter {}
  };

  modules.readline = {
    createInterface: unsupported('readline.createInterface'),
    emitKeypressEvents: unsupported('readline.emitKeypressEvents')
  };

  modules.tty = {
    isatty: function () {
      return false;
    }
  };

  function traceWebSocket(event) {
    global.__playwright4jWsEvents = global.__playwright4jWsEvents || [];
    const text = String(event);
    global.__playwright4jWsEvents.push(text.length > 500 ? text.substring(0, 500) + '...' : text);

    while (global.__playwright4jWsEvents.length > 24) {
      global.__playwright4jWsEvents.shift();
    }
  }

  class HostBackedWebSocket extends EventEmitter {
    constructor(url) {
      super();
      this.url = String(url);
      this.readyState = HostBackedWebSocket.CONNECTING;

      try {
        this.connectionId = host.webSocketClient().open(this.url);
        this.readyState = HostBackedWebSocket.OPEN;
        traceWebSocket('open:' + this.url);
        Promise.resolve().then(() => {
          this.emit('upgrade', { rawHeaders: [] });
          this.emit('open');
        });
      } catch (error) {
        this.readyState = HostBackedWebSocket.CLOSED;
        traceWebSocket('open-error:' + String(error));
        Promise.resolve().then(() => this.emit('error', { message: String(error), type: 'error' }));
      }
    }

    addEventListener(name, listener) {
      return this.on(name, listener);
    }

    send(message) {
      traceWebSocket('send:' + String(message));
      const response = host.webSocketClient().sendAndWait(this.connectionId, String(message));

      if (response) {
        traceWebSocket('message:' + response);
        Promise.resolve().then(() => this.emit('message', { data: response }));
      }
    }

    close() {
      if (this.readyState === HostBackedWebSocket.CLOSED) {
        return;
      }

      this.readyState = HostBackedWebSocket.CLOSED;
      traceWebSocket('close:' + this.url);
      host.webSocketClient().close(this.connectionId);
      this.emit('close', { code: 1000, reason: 'playwright4j' });
    }
  }

  HostBackedWebSocket.CONNECTING = 0;
  HostBackedWebSocket.OPEN = 1;
  HostBackedWebSocket.CLOSING = 2;
  HostBackedWebSocket.CLOSED = 3;
  modules.ws = HostBackedWebSocket;
  modules.ws.WebSocket = HostBackedWebSocket;

  function requestOptionsToUrl(options) {
    if (typeof options === 'string') {
      return options;
    }

    if (options && options.href) {
      return String(options.href);
    }

    const protocol = options && options.protocol || 'http:';
    const hostname = options && (options.hostname || options.host) || '127.0.0.1';
    const port = options && options.port ? ':' + options.port : '';
    const path = options && (options.path || ((options.pathname || '/') + (options.search || ''))) || '/';
    return protocol + '//' + hostname + port + path;
  }

  function createHttpRequest(defaultProtocol, first, second, third) {
    let options = first || {};
    let callback = typeof second === 'function' ? second : third;

    if (second && typeof second === 'object') {
      options = Object.assign({}, first || {}, second);
    }

    if (!options.protocol) {
      options.protocol = defaultProtocol;
    }

    const request = new EventEmitter();
    const chunks = [];

    request.write = function (chunk) {
      chunks.push(String(chunk));
    };

    request.end = function (chunk) {
      if (chunk !== undefined) {
        chunks.push(String(chunk));
      }

      Promise.resolve().then(function () {
        try {
          const method = String(options.method || 'GET').toUpperCase();
          const response = host.httpClient().request(method, requestOptionsToUrl(options), chunks.join(''));
          const incomingMessage = new EventEmitter();
          incomingMessage.statusCode = response.statusCode();
          incomingMessage.headers = {};
          incomingMessage.setEncoding = function () {
            return incomingMessage;
          };

          if (callback) {
            callback(incomingMessage);
          }

          Promise.resolve().then(function () {
            const body = response.body() || '';

            if (body.length > 0) {
              incomingMessage.emit('data', body);
            }

            incomingMessage.emit('end');
          });
        } catch (error) {
          request.emit('error', error);
        }
      });
    };

    request.abort = function () {
      request.emit('abort');
    };

    request.destroy = function (error) {
      if (error) {
        request.emit('error', error);
      }
    };

    request.setTimeout = function () {
      return request;
    };

    return request;
  }

  modules.http = {
    Agent: class Agent {
      constructor(options) {
        this.options = options || {};
      }
    },
    request: function (first, second, third) {
      return createHttpRequest('http:', first, second, third);
    },
    get: function (first, second, third) {
      const request = createHttpRequest('http:', first, second, third);
      request.end();
      return request;
    }
  };
  modules.https = {
    Agent: class Agent {
      constructor(options) {
        this.options = options || {};
      }
    },
    request: function (first, second, third) {
      return createHttpRequest('https:', first, second, third);
    },
    get: function (first, second, third) {
      const request = createHttpRequest('https:', first, second, third);
      request.end();
      return request;
    }
  };
  modules.http2 = {};
  modules.dns = {
    lookup: unsupported('dns.lookup'),
    resolve: unsupported('dns.resolve')
  };
  if (typeof global.URL !== 'function') {
    global.URL = class URL {
      constructor(value, base) {
        const text = base && !String(value).match(/^[a-zA-Z][a-zA-Z0-9+.-]*:/)
          ? String(base).replace(/\/?$/, '/') + String(value).replace(/^\//, '')
          : String(value);
        const match = text.match(/^([a-zA-Z][a-zA-Z0-9+.-]*:)\/\/([^\/:?#]+)(?::(\d+))?([^?#]*)(\?[^#]*)?(#.*)?$/);

        if (!match) {
          throw new TypeError('Invalid URL: ' + text);
        }

        this.href = text;
        this.protocol = match[1];
        this.hostname = match[2];
        this.port = match[3] || '';
        this.host = this.port ? this.hostname + ':' + this.port : this.hostname;
        this.origin = this.protocol + '//' + this.host;
        this.pathname = match[4] || '/';
        this.search = match[5] || '';
        this.hash = match[6] || '';
      }

      toString() {
        return this.protocol + '//' + this.host + (this.pathname || '/') + (this.search || '') + (this.hash || '');
      }
    };
  }

  if (typeof global.URLSearchParams !== 'function') {
    global.URLSearchParams = class URLSearchParams {
      constructor() {}
      toString() {
        return '';
      }
    };
  }

  modules.net = {
    Socket: class Socket extends modules.events.EventEmitter {
      constructor() {
        super();
      }

      connect() {
        return unsupported('net.Socket.connect')();
      }

      destroy() {
        return this;
      }
    },
    Server: class Server extends modules.events.EventEmitter {
      constructor(connectionListener) {
        super();

        if (connectionListener) {
          this.on('connection', connectionListener);
        }
      }

      listen() {
        return unsupported('net.Server.listen')();
      }

      close(callback) {
        if (callback) {
          callback();
        }

        return this;
      }
    },
    isIP: function (value) {
      return /^\d+\.\d+\.\d+\.\d+$/.test(String(value)) ? 4 : 0;
    },
    isIPv4: function (value) {
      return /^\d+\.\d+\.\d+\.\d+$/.test(String(value));
    },
    isIPv6: function (value) {
      return String(value).indexOf(':') >= 0;
    },
    createConnection: unsupported('net.createConnection')
  };
  modules.tls = {};
  modules.url = {
    URL: global.URL,
    URLSearchParams: global.URLSearchParams
  };

  global.process = {
    env: new Proxy({}, {
      get: function (target, name) {
        return host.environment().getEnvironmentValue(String(name));
      }
    }),
    argv: [],
    platform: host.environment().platform(),
    arch: host.environment().architecture(),
    cwd: function () {
      return host.environment().currentWorkingDirectory();
    },
    stdin: {
      fd: 0,
      isTTY: false,
      on: function () {
        return this;
      }
    },
    stdout: {
      fd: 1,
      isTTY: false,
      write: function () {
        return true;
      }
    },
    stderr: {
      fd: 2,
      isTTY: false,
      write: function () {
        return true;
      }
    },
    nextTick: function (callback) {
      return Promise.resolve().then(callback);
    },
    version: 'v20.0.0',
    versions: {
      node: '20.0.0'
    }
  };

  modules.process = global.process;

  global.Buffer = {
    from: function (value) {
      if (typeof value === 'string') {
        return new TextEncoder().encode(value);
      }
      return value;
    },
    byteLength: function (value) {
      return global.Buffer.from(value).length;
    },
    isBuffer: function () {
      return false;
    },
    alloc: function (size) {
      return new Uint8Array(size);
    }
  };

  modules.buffer = {
    Buffer: global.Buffer
  };

  global.setImmediate = function (callback) {
    return setTimeout(callback, 0);
  };

  global.clearImmediate = function (handle) {
    return clearTimeout(handle);
  };

  global.performance = {
    now: function () {
      return Date.now();
    }
  };

  const commonJsModuleCache = {};

  function normalizeResourceName(resourceName) {
    const parts = String(resourceName).replace(/\\/g, '/').split('/');
    const normalizedParts = [];

    for (let index = 0; index < parts.length; index++) {
      const part = parts[index];

      if (!part || part === '.') {
        continue;
      }

      if (part === '..') {
        normalizedParts.pop();
        continue;
      }

      normalizedParts.push(part);
    }

    return normalizedParts.join('/');
  }

  function dirname(resourceName) {
    const normalizedName = normalizeResourceName(resourceName);
    const lastSeparatorIndex = normalizedName.lastIndexOf('/');

    if (lastSeparatorIndex < 0) {
      return '';
    }

    return normalizedName.substring(0, lastSeparatorIndex);
  }

  function resolveRelativeModule(name, parentResourceName) {
    if (!global.__playwright4jDriverBundleSource) {
      return null;
    }

    const parentDirectory = dirname(parentResourceName);
    const rawCandidate = normalizeResourceName(parentDirectory + '/' + name);
    const hasExplicitExtension = /\.[^/]+$/.test(rawCandidate);
    const candidates = hasExplicitExtension ? [
      rawCandidate
    ] : [
      rawCandidate + '.js',
      rawCandidate + '.json',
      rawCandidate + '/index.js',
      rawCandidate
    ];

    for (let index = 0; index < candidates.length; index++) {
      const candidate = candidates[index];

      if (global.__playwright4jDriverBundleSource.hasResource(candidate)) {
        return candidate;
      }
    }

    return null;
  }

  function commandNameFromDeclaration(declaration) {
    return String(declaration).split(' ')[0].split('[')[0];
  }

  function firstCommandArgument(argv) {
    const args = Array.prototype.slice.call(argv || []);

    for (let index = 2; index < args.length; index++) {
      const argument = String(args[index]);

      if (argument && argument.indexOf('-') !== 0) {
        return argument;
      }
    }

    return '';
  }

  function createCommandStub(commandName) {
    const state = {
      commandName: commandName,
      action: null
    };
    let proxy;
    const callable = function () {
      return proxy;
    };

    proxy = new Proxy(callable, {
      get: function (target, property) {
        if (property === '__playwright4jCommandState') {
          return state;
        }

        if (property === 'action') {
          return function (callback) {
            state.action = callback;
            return proxy;
          };
        }

        if (property === 'opts') {
          return function () {
            return {};
          };
        }

        if (property === Symbol.toPrimitive) {
          return function () {
            return commandName;
          };
        }

        return function () {
          return proxy;
        };
      }
    });

    return proxy;
  }

  function createProgramStub() {
    const commands = {};
    let proxy;
    const callable = function () {
      return proxy;
    };

    proxy = new Proxy(callable, {
      get: function (target, property) {
        if (property === 'command') {
          return function (declaration) {
            const commandName = commandNameFromDeclaration(declaration);
            const command = createCommandStub(commandName);
            commands[commandName] = command;
            return command;
          };
        }

        if (property === 'parse' || property === 'parseAsync') {
          return function (argv) {
            const selectedCommandName = firstCommandArgument(argv || global.process.argv);
            const command = commands[selectedCommandName];
            global.__playwright4jSelectedCommand = selectedCommandName;

            if (command && command.__playwright4jCommandState.action) {
              return command.__playwright4jCommandState.action({});
            }

            return proxy;
          };
        }

        if (property === 'opts') {
          return function () {
            return {};
          };
        }

        if (property === 'commands') {
          return Object.keys(commands).map(function (name) {
            return commands[name];
          });
        }

        if (property === Symbol.toPrimitive) {
          return function () {
            return '';
          };
        }

        return function () {
          return proxy;
        };
      }
    });

    return proxy;
  }

  function createProgramOptionStub() {
    return function ProgramOption() {
      return createCommandStub('option');
    };
  }

  function createHostBackedWebSocketTransport() {
    const activeTransports = [];

    global.__playwright4jDrainTransports = function () {
      activeTransports.slice().forEach(function (transport) {
        transport.drain();
      });
    };

    return class HostBackedWebSocketTransport {
      constructor(url, connectionId) {
        this.wsEndpoint = url;
        this.connectionId = connectionId;
        this.headers = [];
        this.onmessage = undefined;
        this.onclose = undefined;
      }

      static async connect(progress, url) {
        progress?.log('<ws connecting> ' + url);
        traceWebSocket('transport-connect:' + url);
        const connectionId = host.webSocketClient().open(String(url));
        progress?.log('<ws connected> ' + url);
        traceWebSocket('transport-connected:' + url);
        const transport = new HostBackedWebSocketTransport(String(url), connectionId);
        activeTransports.push(transport);
        return transport;
      }

      emitMessages(joinedMessages) {
        String(joinedMessages || '').split('\u001e').forEach((message) => {
          if (!message) {
            return;
          }

          Promise.resolve().then(() => {
            if (this.onmessage) {
              this.onmessage(JSON.parse(message));
            } else {
              traceWebSocket('transport-message-dropped:no-onmessage');
            }
          });
        });
      }

      send(message) {
        traceWebSocket('transport-send:' + JSON.stringify(message));
        const response = host.webSocketClient().sendAndWait(this.connectionId, JSON.stringify(message));
        traceWebSocket('transport-message:' + response);
        this.emitMessages(response);
      }

      drain() {
        const drainedMessages = host.webSocketClient().drain(this.connectionId, 10);

        if (drainedMessages) {
          traceWebSocket('transport-drain:' + drainedMessages);
          this.emitMessages(drainedMessages);
        }
      }

      close() {
        host.webSocketClient().close(this.connectionId);

        if (this.onclose) {
          this.onclose('playwright4j');
        }
      }

      async closeAndWait() {
        this.close();
      }
    };
  }

  function installKnownModuleFallbacks(resourceName, exportsObject) {
    if (resourceName.endsWith('/lib/server/transport.js')) {
      const HostBackedWebSocketTransport = createHostBackedWebSocketTransport();

      return new Proxy(exportsObject, {
        get: function (target, property) {
          if (property === 'WebSocketTransport') {
            return HostBackedWebSocketTransport;
          }

          return target[property];
        }
      });
    }

    if (resourceName.endsWith('/lib/utilsBundle.js') && exportsObject.program === undefined) {
      return new Proxy(exportsObject, {
        get: function (target, property) {
          if (property === 'program') {
            return createProgramStub();
          }

          if (property === 'ProgramOption') {
            return createProgramOptionStub();
          }

          return target[property];
        }
      });
    }

    return exportsObject;
  }

  function createInitialModuleExports(resourceName) {
    const exportsObject = {};

    if (resourceName.endsWith('/lib/utils.js') || resourceName.endsWith('/lib/utils/index.js')) {
      return new Proxy(exportsObject, {
        get: function (target, property) {
          if (property === 'assert') {
            return modules.assert;
          }

          return target[property];
        }
      });
    }

    return exportsObject;
  }

  function loadCommonJsModule(resourceName) {
    const normalizedResourceName = normalizeResourceName(resourceName);

    if (commonJsModuleCache[normalizedResourceName]) {
      return commonJsModuleCache[normalizedResourceName].exports;
    }

    const module = { exports: createInitialModuleExports(normalizedResourceName) };
    commonJsModuleCache[normalizedResourceName] = module;

    let source = global.__playwright4jDriverBundleSource.readResource(normalizedResourceName);

    if (normalizedResourceName.endsWith('.json')) {
      module.exports = JSON.parse(source);
      Object.defineProperty(module.exports, 'default', {
        value: module.exports,
        enumerable: false,
        configurable: true
      });
      return module.exports;
    }

    const factory = new Function('require', 'module', 'exports', '__filename', '__dirname', source);

    try {
      global.__playwright4jCurrentModule = normalizedResourceName;
      factory(createRequire(normalizedResourceName), module, module.exports, normalizedResourceName, dirname(normalizedResourceName));
    } catch (error) {
      error.message = error.message + ' while loading ' + normalizedResourceName;
      throw error;
    }

    module.exports = installKnownModuleFallbacks(normalizedResourceName, module.exports);

    return module.exports;
  }

  function createRequire(parentResourceName) {
    const requireFunction = function require(name) {
      const normalizedName = name.indexOf('node:') === 0 ? name.substring(5) : name;

      if (modules[normalizedName]) {
        return modules[normalizedName];
      }

      if (name === '.' || name === '..' || name.indexOf('./') === 0 || name.indexOf('../') === 0) {
        const resolvedResourceName = resolveRelativeModule(name, parentResourceName);

        if (resolvedResourceName) {
          return loadCommonJsModule(resolvedResourceName);
        }
      }

      reportMissing('require(' + name + ')');
      throw new Error('Unsupported Playwright4J module: ' + name);
    };

    requireFunction.resolve = function resolve(name) {
      const normalizedName = name.indexOf('node:') === 0 ? name.substring(5) : name;

      if (modules[normalizedName]) {
        return normalizedName;
      }

      if (name === '.' || name === '..' || name.indexOf('./') === 0 || name.indexOf('../') === 0) {
        const resolvedResourceName = resolveRelativeModule(name, parentResourceName);

        if (resolvedResourceName) {
          return resolvedResourceName;
        }
      }

      reportMissing('require.resolve(' + name + ')');
      throw new Error('Unsupported Playwright4J module resolution: ' + name);
    };

    return requireFunction;
  }

  global.require = createRequire('');
  global.__playwright4jCreateRequire = createRequire;
  global.__playwright4jDirname = dirname;
  global.__playwright4jModules = modules;
})(globalThis);
