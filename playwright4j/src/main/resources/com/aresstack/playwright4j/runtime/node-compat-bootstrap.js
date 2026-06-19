(function installPlaywright4JCompatibilityLayer(global) {
  const modules = {};
  const host = global.__playwright4jHost;

  global.global = global;

  // Keep all console output off the protocol channel (System.out is reserved for the
  // length-prefixed driver protocol). Route everything to the host stderr pipe.
  function consoleWrite(args) {
    try {
      const text = Array.prototype.map.call(args, function (value) {
        if (typeof value === 'string') {
          return value;
        }
        try {
          return JSON.stringify(value);
        } catch (error) {
          return String(value);
        }
      }).join(' ');
      host.driverPipe().writeErr(text + '\n');
    } catch (error) {
      // Diagnostics must never break the run.
    }
  }
  global.console = {
    log: function () { consoleWrite(arguments); },
    info: function () { consoleWrite(arguments); },
    warn: function () { consoleWrite(arguments); },
    error: function () { consoleWrite(arguments); },
    debug: function () { consoleWrite(arguments); },
    trace: function () { consoleWrite(arguments); },
    dir: function () { consoleWrite(arguments); },
    assert: function () {}
  };

  function pw4jDebug(message) {
    try {
      host.debugLog(String(message));
    } catch (error) {
      // Diagnostics must never break the run.
    }
  }
  global.__pw4jDebug = pw4jDebug;

  // Timers are driven by the Java pump (GraalDriverMain) which repeatedly fires due
  // timers via __playwright4jRunDueTimers(). Zero-delay timers take the microtask fast
  // path so they still resolve within the current evaluation. Delayed timers are stored
  // in a queue and fired once their wall-clock deadline passes. This makes Playwright's
  // progress timeouts and polling waits work without any background thread.
  let timerSequence = 0;
  const canceledTimers = {};
  const pendingTimers = new Map();

  function scheduleTimer(callback, delay, args) {
    const handle = ++timerSequence;
    const numericDelay = typeof delay === 'number' && delay > 0 ? delay : 0;

    if (numericDelay === 0) {
      Promise.resolve().then(function () {
        if (!canceledTimers[handle]) {
          delete canceledTimers[handle];
          callback.apply(null, args);
        }
      });
      return handle;
    }

    pendingTimers.set(handle, {
      due: Date.now() + numericDelay,
      callback: callback,
      args: args
    });
    return handle;
  }

  function clearTimer(handle) {
    canceledTimers[handle] = true;
    pendingTimers.delete(handle);
  }

  if (typeof global.setTimeout !== 'function') {
    global.setTimeout = function (callback, delay) {
      return scheduleTimer(callback, delay, Array.prototype.slice.call(arguments, 2));
    };
  }

  if (typeof global.clearTimeout !== 'function') {
    global.clearTimeout = clearTimer;
  }

  if (typeof global.setInterval !== 'function') {
    // Playwright's bundled driver does not use setInterval, but provide a safe shim.
    global.setInterval = function () {
      return ++timerSequence;
    };
  }

  if (typeof global.clearInterval !== 'function') {
    global.clearInterval = clearTimer;
  }

  // Fires every timer whose deadline has passed. Returns the number fired so the Java
  // pump can tell whether progress was made this tick.
  global.__playwright4jRunDueTimers = function () {
    if (pendingTimers.size === 0) {
      return 0;
    }

    const now = Date.now();
    const due = [];
    pendingTimers.forEach(function (timer, handle) {
      if (timer.due <= now) {
        due.push({ handle: handle, timer: timer });
      }
    });

    due.sort(function (left, right) {
      return left.timer.due - right.timer.due;
    });

    let fired = 0;
    for (let index = 0; index < due.length; index++) {
      const entry = due[index];
      if (pendingTimers.delete(entry.handle) && !canceledTimers[entry.handle]) {
        fired++;
        try {
          entry.timer.callback.apply(null, entry.timer.args);
        } catch (error) {
          host.driverPipe().writeErr('[playwright4j] timer callback failed: ' + String(error && error.stack || error) + '\n');
        }
      }
    }

    return fired;
  };

  global.__playwright4jHasPendingTimers = function () {
    return pendingTimers.size > 0;
  };

  function reportMissing(name) {
    host.missingHostFunctionReporter().reportMissingHostFunction(name);
  }

  function unsupported(name) {
    return function unsupportedFunction() {
      reportMissing(name);
      throw new Error('Missing Playwright4J host implementation: ' + name);
    };
  }

  function hostExists(path) {
    try {
      return host.fileSystem().existsSync(String(path));
    } catch (error) {
      return false;
    }
  }

  modules.fs = {
    existsSync: function (path) {
      return hostExists(path);
    },
    readFileSync: function (path, encoding) {
      return host.fileSystem().readFileSync(String(path), encoding || 'utf8');
    },
    accessSync: function (path) {
      if (!hostExists(path)) {
        throw new Error('ENOENT: no such file or directory, access ' + String(path));
      }
    },
    writeFileSync: function (path, content) {
      host.fileSystem().writeFile(String(path), content === undefined ? '' : String(content));
    },
    mkdirSync: function (path) {
      host.fileSystem().createDirectories(String(path));
      return undefined;
    },
    mkdtempSync: function (prefix) {
      return host.fileSystem().createTempDirectory(String(prefix));
    },
    rmSync: function () {
      return undefined;
    },
    stat: function (path, callback) {
      const exists = hostExists(path);
      Promise.resolve().then(function () {
        if (exists) {
          callback(null, { isFile: function () { return true; }, isDirectory: function () { return false; } });
        } else {
          const error = new Error('ENOENT: no such file or directory, stat ' + String(path));
          error.code = 'ENOENT';
          callback(error);
        }
      });
    },
    promises: {
      readFile: async function (path, encoding) {
        return host.fileSystem().readFileSync(String(path), encoding || 'utf8');
      },
      mkdtemp: async function (prefix) {
        return host.fileSystem().createTempDirectory(String(prefix));
      },
      stat: async function (path) {
        if (!hostExists(path)) {
          const error = new Error('ENOENT: no such file or directory, stat ' + String(path));
          error.code = 'ENOENT';
          throw error;
        }
        return {
          mtime: new Date(0),
          mtimeMs: 0,
          size: 0,
          isFile: function () { return true; },
          isDirectory: function () { return false; }
        };
      },
      writeFile: async function (path, content) {
        host.fileSystem().writeFile(String(path), content === undefined ? '' : String(content));
        return undefined;
      },
      mkdir: async function (path) {
        host.fileSystem().createDirectories(String(path));
        return undefined;
      },
      rm: async function () {
        return undefined;
      },
      access: async function (path) {
        if (!hostExists(path)) {
          throw new Error('ENOENT: no such file or directory, access ' + String(path));
        }
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

  const ARGUMENT_SEPARATOR = '';

  function createProcessStream() {
    const stream = new EventEmitter();
    stream.readable = true;
    stream.writable = true;
    stream.write = function () {
      return true;
    };
    stream.end = function () {
      return undefined;
    };
    stream.destroy = function () {
      return undefined;
    };
    stream.resume = function () {
      return stream;
    };
    stream.pause = function () {
      return stream;
    };
    stream.setEncoding = function () {
      return stream;
    };
    stream.pipe = function (destination) {
      return destination;
    };
    return stream;
  }

  // stdio[3] and stdio[4] stand in for Chromium's remote-debugging pipe. Because we use a
  // WebSocket endpoint instead, they carry the discovered ws:// endpoint so that the
  // host-backed PipeTransport can connect to it.
  function createBrowserPipe(processId, endpoint, child) {
    const pipe = createProcessStream();
    pipe.__playwright4jBrowserPipe = true;
    pipe.__playwright4jProcessId = processId;
    pipe.__playwright4jEndpoint = endpoint;
    pipe.__playwright4jChildProcess = child;
    return pipe;
  }

  function closeSpawnedProcess(child, exitCode) {
    if (!child || child.__playwright4jClosed) {
      return;
    }
    child.__playwright4jClosed = true;
    child.killed = true;
    const code = typeof exitCode === 'number' ? exitCode : 0;
    child.emit('exit', code, null);
    child.emit('close', code, null);
  }

  function createSpawnedProcess(processId, endpoint) {
    const child = new EventEmitter();
    child.pid = Math.floor(Math.random() * 1000000) + 1;
    child.killed = false;
    child.__playwright4jClosed = false;
    child.__playwright4jProcessId = processId;
    child.stdin = createProcessStream();
    child.stdout = createProcessStream();
    child.stderr = createProcessStream();
    child.stdio = [
      child.stdin,
      child.stdout,
      child.stderr,
      createBrowserPipe(processId, endpoint, child),
      createBrowserPipe(processId, endpoint, child)
    ];
    child.kill = function () {
      if (!child.__playwright4jClosed) {
        try {
          host.processLauncher().close(processId);
        } catch (error) {
          // Already gone.
        }
        closeSpawnedProcess(child, 0);
      }
      return true;
    };
    child.ref = function () {
      return child;
    };
    child.unref = function () {
      return child;
    };
    Promise.resolve().then(function () {
      child.emit('spawn');
    });
    return child;
  }

  modules.child_process = {
    spawn: function (command, args, options) {
      const joinedArguments = (args || []).map(function (value) {
        return String(value);
      }).join(ARGUMENT_SEPARATOR);
      const workingDirectory = options && options.cwd ? String(options.cwd) : global.process.cwd();

      // launchChromium throws on failure; let it propagate so Playwright's launch promise
      // rejects with a real error instead of hanging.
      const result = String(host.processLauncher().launchChromium(String(command), joinedArguments, workingDirectory));
      const separatorIndex = result.indexOf(ARGUMENT_SEPARATOR);
      const processId = separatorIndex < 0 ? result : result.substring(0, separatorIndex);
      const endpoint = separatorIndex < 0 ? '' : result.substring(separatorIndex + 1);
      return createSpawnedProcess(processId, endpoint);
    },
    execFile: unsupported('child_process.execFile'),
    execFileSync: unsupported('child_process.execFileSync'),
    spawnSync: function () {
      return { status: 0, stdout: global.Buffer.from(''), stderr: global.Buffer.from('') };
    }
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
    createInterface: function () {
      const reader = new EventEmitter();
      reader.close = function () {
        reader.emit('close');
        return undefined;
      };
      return reader;
    },
    emitKeypressEvents: function () {
      return undefined;
    }
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

  global.process = new EventEmitter();
  Object.assign(global.process, {
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
      readable: true,
      listenersByName: {},
      emit: EventEmitter.prototype.emit,
      on: EventEmitter.prototype.on,
      once: EventEmitter.prototype.once,
      off: EventEmitter.prototype.off
    },
    stdout: {
      fd: 1,
      isTTY: false,
      write: function (chunk) {
        // process.stdout must never reach the length-prefixed protocol channel, which is
        // driven exclusively by the host pipe's writeMessage. Route it to stderr.
        return host.driverPipe().writeErr(String(chunk));
      }
    },
    stderr: {
      fd: 2,
      isTTY: false,
      write: function (chunk) {
        return host.driverPipe().writeErr(String(chunk));
      }
    },
    nextTick: function (callback) {
      const args = Array.prototype.slice.call(arguments, 1);
      return Promise.resolve().then(function () {
        callback.apply(null, args);
      });
    },
    exit: function () {
      return undefined;
    },
    kill: function () {
      return true;
    },
    version: 'v20.0.0',
    versions: {
      node: '20.0.0'
    }
  });

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
    concat: function (values) {
      return {
        toString: function () {
          return values.map(function (value) {
            return String(value);
          }).join('');
        }
      };
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

  function createHostBackedPipeTransport() {
    const activeBrowserPipes = [];

    function emitBrowserMessages(transport, joinedMessages) {
      let emitted = 0;
      String(joinedMessages || '').split('').forEach(function (message) {
        if (!message) {
          return;
        }
        emitted++;
        Promise.resolve().then(function () {
          if (transport.onmessage) {
            transport.onmessage(JSON.parse(message));
          }
        });
      });
      return emitted;
    }

    // Drains every browser CDP pipe; returns how many raw messages were pulled so the
    // Java pump can detect progress.
    global.__playwright4jDrainBrowserPipes = function () {
      let drained = 0;
      activeBrowserPipes.slice().forEach(function (transport) {
        drained += transport.drain();
      });
      return drained;
    };

    return class HostBackedPipeTransport {
      constructor(pipeWrite, pipeRead) {
        this.onmessage = undefined;
        this.onclose = undefined;
        this.browserConnectionId = undefined;
        this.browserProcessId = undefined;
        this.browserChildProcess = undefined;

        if (pipeWrite && pipeWrite.__playwright4jBrowserPipe) {
          // Chromium CDP pipe: bridge it to the WebSocket endpoint Chrome chose.
          this.browserProcessId = String(pipeWrite.__playwright4jProcessId);
          this.browserChildProcess = pipeWrite.__playwright4jChildProcess;
          this.browserConnectionId = host.webSocketClient().open(String(pipeWrite.__playwright4jEndpoint));
          activeBrowserPipes.push(this);
        } else {
          // Driver protocol pipe: talk to the Java client over the length-prefixed pipe.
          global.__playwright4jProtocolTransport = this;
        }
      }

      send(message) {
        if (this.browserConnectionId) {
          const payload = typeof message === 'string' ? message : JSON.stringify(message);
          const response = host.webSocketClient().sendAndWait(this.browserConnectionId, payload);
          emitBrowserMessages(this, response);
          return undefined;
        }

        return host.driverPipe().writeMessage(String(message));
      }

      drain() {
        if (!this.browserConnectionId) {
          return 0;
        }
        const response = host.webSocketClient().drain(this.browserConnectionId, 10);
        return emitBrowserMessages(this, response);
      }

      close() {
        const index = activeBrowserPipes.indexOf(this);
        if (index >= 0) {
          activeBrowserPipes.splice(index, 1);
        }

        if (this.browserConnectionId) {
          host.webSocketClient().close(this.browserConnectionId);
          this.browserConnectionId = undefined;
        }

        if (this.browserProcessId) {
          try {
            host.processLauncher().close(this.browserProcessId);
          } catch (error) {
            // Already gone.
          }
          this.browserProcessId = undefined;
        }

        if (this.browserChildProcess && !this.browserChildProcess.killed) {
          closeSpawnedProcess(this.browserChildProcess, 0);
          this.browserChildProcess = undefined;
        }

        if (this.onclose) {
          this.onclose();
        }
      }

      _deliver(message) {
        if (this.onmessage) {
          this.onmessage(String(message));
        }
      }
    };
  }

  function createHostBackedWebSocketTransport() {
    const activeTransports = [];

    global.__playwright4jDrainTransports = function () {
      let drained = 0;
      activeTransports.slice().forEach(function (transport) {
        drained += transport.drain();
      });
      return drained;
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
        let emitted = 0;
        String(joinedMessages || '').split('').forEach((message) => {
          if (!message) {
            return;
          }

          emitted++;
          Promise.resolve().then(() => {
            if (this.onmessage) {
              this.onmessage(JSON.parse(message));
            } else {
              traceWebSocket('transport-message-dropped:no-onmessage');
            }
          });
        });
        return emitted;
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
          return this.emitMessages(drainedMessages);
        }

        return 0;
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
    if (resourceName.endsWith('/pipeTransport.js')) {
      const HostBackedPipeTransport = createHostBackedPipeTransport();

      return new Proxy(exportsObject, {
        get: function (target, property) {
          if (property === 'PipeTransport') {
            return HostBackedPipeTransport;
          }

          return target[property];
        }
      });
    }

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

  global.__playwright4jDriverPipeDeliver = function (message) {
    if (global.__playwright4jProtocolTransport) {
      global.__playwright4jProtocolTransport._deliver(String(message));
      return;
    }

    global.process.stdin.emit('data', String(message));
  };

  global.require = createRequire('');
  global.__playwright4jCreateRequire = createRequire;
  global.__playwright4jDirname = dirname;
  global.__playwright4jModules = modules;
})(globalThis);
