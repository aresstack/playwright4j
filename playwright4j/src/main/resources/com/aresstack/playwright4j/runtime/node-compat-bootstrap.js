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

  function flattenRequestHeaders(options) {
    const headers = options && options.headers;
    if (!headers || typeof headers !== 'object') {
      return '';
    }
    const entries = [];
    Object.keys(headers).forEach(function (name) {
      const value = headers[name];
      if (value === undefined || value === null) {
        return;
      }
      if (Array.isArray(value)) {
        value.forEach(function (item) {
          entries.push(name);
          entries.push(String(item));
        });
      } else {
        entries.push(name);
        entries.push(String(value));
      }
    });
    return entries.join('');
  }

  function buildIncomingMessage(response) {
    const incomingMessage = new EventEmitter();
    incomingMessage.statusCode = response.statusCode();
    incomingMessage.statusMessage = response.statusText();
    incomingMessage.httpVersion = '1.1';
    incomingMessage.complete = false;

    const rawHeaders = [];
    const headers = {};
    const headerValues = String(response.rawHeaders() || '').split('');
    for (let index = 0; index + 1 < headerValues.length; index += 2) {
      const name = headerValues[index];
      const value = headerValues[index + 1];
      if (!name) {
        continue;
      }
      rawHeaders.push(name, value);
      const lowerName = name.toLowerCase();
      if (headers[lowerName] === undefined) {
        headers[lowerName] = value;
      } else {
        headers[lowerName] = headers[lowerName] + ', ' + value;
      }
    }

    incomingMessage.headers = headers;
    incomingMessage.rawHeaders = rawHeaders;
    incomingMessage.setEncoding = function () {
      return incomingMessage;
    };
    incomingMessage.resume = function () {
      return incomingMessage;
    };
    return incomingMessage;
  }

  function toBodyBuffer(chunk, encoding) {
    if (chunk === undefined || chunk === null) {
      return null;
    }
    if (global.Buffer.isBuffer(chunk)) {
      return chunk;
    }
    if (chunk instanceof Uint8Array) {
      return global.Buffer.from(chunk);
    }
    return global.Buffer.from(String(chunk), typeof encoding === 'string' ? encoding : 'utf8');
  }

  // In-flight async HTTP requests, polled by the Java pump so the GraalJS thread is never
  // blocked on I/O and Playwright's progress timeouts can fire mid-request.
  const pendingHttpRequests = [];

  function deliverHttpResponse(entry, response) {
    const request = entry.request;
    const errorMessage = response.errorMessage();
    if (errorMessage) {
      const networkError = new Error(errorMessage);
      const errorCode = response.errorCode();
      if (errorCode) {
        networkError.code = errorCode;
      }
      request.emit('error', networkError);
      return;
    }

    const incomingMessage = buildIncomingMessage(response);
    if (entry.callback) {
      entry.callback(incomingMessage);
    }
    request.emit('response', incomingMessage);

    // Let the (async) response handler register its body listeners before the body flows.
    Promise.resolve().then(function () {
      const body = global.Buffer.from(String(response.bodyBase64() || ''), 'base64');
      if (body.length > 0) {
        incomingMessage.emit('data', body);
      }
      incomingMessage.complete = true;
      incomingMessage.emit('end');
      incomingMessage.emit('close');
      request.emit('close');
    });
  }

  global.__playwright4jDrainHttp = function () {
    let delivered = 0;
    for (let index = pendingHttpRequests.length - 1; index >= 0; index--) {
      const entry = pendingHttpRequests[index];
      const response = host.httpClient().pollRequest(entry.requestId);
      if (response === null || response === undefined) {
        continue;
      }
      pendingHttpRequests.splice(index, 1);
      delivered++;
      deliverHttpResponse(entry, response);
    }
    return delivered;
  };

  function removePendingHttpRequest(request) {
    for (let index = pendingHttpRequests.length - 1; index >= 0; index--) {
      if (pendingHttpRequests[index].request === request) {
        pendingHttpRequests.splice(index, 1);
      }
    }
  }

  function createHttpRequest(defaultProtocol, first, second, third) {
    // Node signatures: request(url[, options][, cb]) or request(options[, cb]).
    let urlString = null;
    let options;
    let callback;

    if (typeof first === 'string' || first instanceof global.URL) {
      urlString = typeof first === 'string' ? first : first.toString();
      if (second && typeof second === 'object') {
        options = second;
        callback = typeof third === 'function' ? third : undefined;
      } else {
        options = {};
        callback = typeof second === 'function' ? second : undefined;
      }
    } else {
      options = first || {};
      callback = typeof second === 'function' ? second : third;
    }

    if (!options.protocol) {
      options.protocol = defaultProtocol;
    }

    // Mirror Node: http/https only accept http(s) URLs. Reject others (e.g. data:, file:)
    // synchronously so Playwright rejects with "Protocol \"X:\" not supported".
    const requestProtocol = (first instanceof global.URL ? first.protocol : options.protocol) || defaultProtocol;
    if (requestProtocol !== 'http:' && requestProtocol !== 'https:') {
      throw new Error('Protocol "' + requestProtocol + '" not supported. Expected "http:"');
    }

    // Node validates header values synchronously and throws on invalid characters (anything
    // outside HTAB / printable ASCII / ISO-8859-1), e.g. multi-byte UTF-16 characters.
    if (options.headers && typeof options.headers === 'object') {
      Object.keys(options.headers).forEach(function (name) {
        const value = options.headers[name];
        if (value === undefined || value === null) {
          return;
        }
        if (/[^\t\x20-\x7e\x80-\xff]/.test(String(value))) {
          throw new Error('Invalid character in header content ["' + name + '"]');
        }
      });
    }

    const request = new EventEmitter();
    const bodyChunks = [];

    request.write = function (chunk, encoding) {
      const buffer = toBodyBuffer(chunk, encoding);
      if (buffer && buffer.length > 0) {
        bodyChunks.push(buffer);
      }
      return true;
    };

    request.setHeader = function (name, value) {
      options.headers = options.headers || {};
      options.headers[name] = value;
    };

    request.getHeader = function (name) {
      return options.headers ? options.headers[name] : undefined;
    };

    request.removeHeader = function (name) {
      if (options.headers) {
        delete options.headers[name];
      }
    };

    request.end = function (chunk, encoding) {
      const finalBuffer = toBodyBuffer(chunk, encoding);
      if (finalBuffer && finalBuffer.length > 0) {
        bodyChunks.push(finalBuffer);
      }

      request.emit('finish');

      let requestId;
      try {
        const method = String(options.method || 'GET').toUpperCase();
        const url = urlString !== null ? urlString : requestOptionsToUrl(options);
        // Body is sent bytes-first (Base64 across the host boundary) so binary, form and
        // multipart payloads survive intact.
        const bodyBase64 = bodyChunks.length > 0 ? global.Buffer.concat(bodyChunks).toString('base64') : '';
        // Non-blocking: the response is delivered later via __playwright4jDrainHttp, so the
        // JS thread stays free and Playwright's timeout/abort can take effect mid-request.
        requestId = host.httpClient().startRequest(method, url, flattenRequestHeaders(options), bodyBase64);
      } catch (error) {
        Promise.resolve().then(function () {
          request.emit('error', error);
        });
        return;
      }

      request.__playwright4jRequestId = requestId;
      pendingHttpRequests.push({ requestId: requestId, request: request, callback: callback });
    };

    function cancelUnderlyingRequest() {
      if (request.__playwright4jRequestId) {
        try {
          host.httpClient().cancelRequest(request.__playwright4jRequestId);
        } catch (error) {
          // Already finished.
        }
        request.__playwright4jRequestId = undefined;
      }
      removePendingHttpRequest(request);
    }

    request.abort = function () {
      cancelUnderlyingRequest();
      request.emit('abort');
    };

    request.destroy = function (error) {
      cancelUnderlyingRequest();
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
  if (typeof global.URLSearchParams !== 'function') {
    global.URLSearchParams = class URLSearchParams {
      constructor(init) {
        this._list = [];
        if (init === undefined || init === null || init === '') {
          return;
        }
        if (init instanceof global.URLSearchParams) {
          init._list.forEach((pair) => this._list.push([pair[0], pair[1]]));
        } else if (Array.isArray(init)) {
          init.forEach((pair) => this._list.push([String(pair[0]), String(pair[1])]));
        } else if (typeof init === 'string') {
          const text = init.charAt(0) === '?' ? init.substring(1) : init;
          if (text) {
            text.split('&').forEach((pair) => {
              if (!pair) {
                return;
              }
              const equals = pair.indexOf('=');
              const rawName = equals < 0 ? pair : pair.substring(0, equals);
              const rawValue = equals < 0 ? '' : pair.substring(equals + 1);
              this._list.push([decodeFormComponent(rawName), decodeFormComponent(rawValue)]);
            });
          }
        } else if (typeof init === 'object') {
          Object.keys(init).forEach((key) => this._list.push([key, String(init[key])]));
        }
      }

      append(name, value) {
        this._list.push([String(name), String(value)]);
      }

      set(name, value) {
        name = String(name);
        value = String(value);
        let replaced = false;
        const next = [];
        for (let index = 0; index < this._list.length; index++) {
          const pair = this._list[index];
          if (pair[0] !== name) {
            next.push(pair);
          } else if (!replaced) {
            next.push([name, value]);
            replaced = true;
          }
        }
        if (!replaced) {
          next.push([name, value]);
        }
        this._list = next;
      }

      get(name) {
        name = String(name);
        const pair = this._list.find((entry) => entry[0] === name);
        return pair ? pair[1] : null;
      }

      getAll(name) {
        name = String(name);
        return this._list.filter((entry) => entry[0] === name).map((entry) => entry[1]);
      }

      has(name) {
        name = String(name);
        return this._list.some((entry) => entry[0] === name);
      }

      delete(name) {
        name = String(name);
        this._list = this._list.filter((entry) => entry[0] !== name);
      }

      forEach(callback, thisArg) {
        this._list.slice().forEach((pair) => callback.call(thisArg, pair[1], pair[0], this));
      }

      keys() {
        return this._list.map((pair) => pair[0])[Symbol.iterator]();
      }

      values() {
        return this._list.map((pair) => pair[1])[Symbol.iterator]();
      }

      entries() {
        return this._list.map((pair) => [pair[0], pair[1]])[Symbol.iterator]();
      }

      [Symbol.iterator]() {
        return this.entries();
      }

      toString() {
        return this._list
          .map((pair) => encodeFormComponent(pair[0]) + '=' + encodeFormComponent(pair[1]))
          .join('&');
      }
    };
  }

  function encodeFormComponent(value) {
    return encodeURIComponent(String(value)).replace(/%20/g, '+');
  }

  function decodeFormComponent(value) {
    try {
      return decodeURIComponent(String(value).replace(/\+/g, ' '));
    } catch (error) {
      return String(value);
    }
  }

  if (typeof global.URL !== 'function') {
    // Removes "." and ".." segments from an absolute path, per RFC 3986.
    const normalizeUrlPath = function (path) {
      const isAbsolute = path.charAt(0) === '/';
      const segments = path.split('/');
      const output = [];
      for (let index = 0; index < segments.length; index++) {
        const segment = segments[index];
        if (segment === '.') {
          continue;
        }
        if (segment === '..') {
          if (output.length > 0 && output[output.length - 1] !== '..') {
            output.pop();
          } else if (!isAbsolute) {
            output.push('..');
          }
          continue;
        }
        output.push(segment);
      }
      let result = output.join('/');
      if (isAbsolute && result.charAt(0) !== '/') {
        result = '/' + result;
      }
      return result;
    };

    global.URL = class URL {
      constructor(value, base) {
        let text = String(value);
        const hasScheme = /^[a-zA-Z][a-zA-Z0-9+.-]*:/.test(text);

        if (!hasScheme && base !== undefined && base !== null) {
          const baseUrl = base instanceof URL ? base : new URL(String(base));
          if (text.indexOf('//') === 0) {
            // Protocol-relative reference.
            text = baseUrl.protocol + text;
          } else if (text.charAt(0) === '/') {
            // Absolute-path reference: replaces the whole base path.
            text = baseUrl.origin + text;
          } else if (text.charAt(0) === '?') {
            text = baseUrl.origin + baseUrl.pathname + text;
          } else if (text.charAt(0) === '#') {
            text = baseUrl.origin + baseUrl.pathname + baseUrl.search + text;
          } else if (text.length === 0) {
            text = baseUrl.origin + baseUrl.pathname + baseUrl.search;
          } else {
            // Relative-path reference: resolve against the base directory.
            const baseDirectory = baseUrl.pathname.replace(/[^/]*$/, '');
            text = baseUrl.origin + normalizeUrlPath(baseDirectory + text);
          }
        }

        const match = text.match(/^([a-zA-Z][a-zA-Z0-9+.-]*:)\/\/([^\/:?#]+)(?::(\d+))?([^?#]*)(\?[^#]*)?(#.*)?$/);

        if (match) {
          this.protocol = match[1];
          this.hostname = match[2];
          this.port = match[3] || '';
          this.pathname = match[4] || '/';
          this.hash = match[6] || '';
          this._searchParams = new global.URLSearchParams(match[5] || '');
          return;
        }

        // Scheme-only / opaque URLs (data:, file:, mailto:, ...). We do not fully parse the
        // authority, but expose the protocol so callers can route/reject correctly.
        const schemeMatch = text.match(/^([a-zA-Z][a-zA-Z0-9+.-]*:)([\s\S]*)$/);
        if (!schemeMatch) {
          throw new TypeError('Invalid URL: ' + text);
        }
        this.protocol = schemeMatch[1];
        this.hostname = '';
        this.port = '';
        let remainder = schemeMatch[2];
        const hashIndex = remainder.indexOf('#');
        if (hashIndex >= 0) {
          this.hash = remainder.substring(hashIndex);
          remainder = remainder.substring(0, hashIndex);
        } else {
          this.hash = '';
        }
        let query = '';
        const queryIndex = remainder.indexOf('?');
        if (queryIndex >= 0) {
          query = remainder.substring(queryIndex + 1);
          remainder = remainder.substring(0, queryIndex);
        }
        this.pathname = remainder;
        this._searchParams = new global.URLSearchParams(query);
      }

      get host() {
        return this.port ? this.hostname + ':' + this.port : this.hostname;
      }

      get origin() {
        return this.protocol + '//' + this.host;
      }

      get search() {
        const serialized = this._searchParams.toString();
        return serialized ? '?' + serialized : '';
      }

      set search(value) {
        this._searchParams = new global.URLSearchParams(String(value));
      }

      get searchParams() {
        return this._searchParams;
      }

      get href() {
        return this.protocol + '//' + this.host + (this.pathname || '/') + this.search + (this.hash || '');
      }

      set href(value) {
        const parsed = new global.URL(String(value));
        this.protocol = parsed.protocol;
        this.hostname = parsed.hostname;
        this.port = parsed.port;
        this.pathname = parsed.pathname;
        this.hash = parsed.hash;
        this._searchParams = parsed._searchParams;
      }

      toString() {
        return this.href;
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

  // A Node-compatible Buffer backed by Uint8Array. Playwright's protocol serializer checks
  // `arg instanceof Buffer` and calls `buffer.toString('base64')`, so binary bodies must be
  // real Buffer instances carrying real bytes (not a stub).
  const BASE64_ALPHABET = 'ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/';

  function base64Encode(bytes) {
    let output = '';
    for (let index = 0; index < bytes.length; index += 3) {
      const byte0 = bytes[index];
      const byte1 = index + 1 < bytes.length ? bytes[index + 1] : 0;
      const byte2 = index + 2 < bytes.length ? bytes[index + 2] : 0;
      output += BASE64_ALPHABET[byte0 >> 2];
      output += BASE64_ALPHABET[((byte0 & 3) << 4) | (byte1 >> 4)];
      output += index + 1 < bytes.length ? BASE64_ALPHABET[((byte1 & 15) << 2) | (byte2 >> 6)] : '=';
      output += index + 2 < bytes.length ? BASE64_ALPHABET[byte2 & 63] : '=';
    }
    return output;
  }

  function base64Decode(text) {
    const clean = String(text).replace(/[^A-Za-z0-9+/]/g, '');
    const length = Math.floor(clean.length * 3 / 4);
    const bytes = new Uint8Array(length);
    let outputIndex = 0;
    for (let index = 0; index < clean.length; index += 4) {
      const enc0 = BASE64_ALPHABET.indexOf(clean[index]);
      const enc1 = BASE64_ALPHABET.indexOf(clean[index + 1]);
      const enc2 = BASE64_ALPHABET.indexOf(clean[index + 2]);
      const enc3 = BASE64_ALPHABET.indexOf(clean[index + 3]);
      if (outputIndex < length) bytes[outputIndex++] = (enc0 << 2) | (enc1 >> 4);
      if (outputIndex < length && enc2 >= 0) bytes[outputIndex++] = ((enc1 & 15) << 4) | (enc2 >> 2);
      if (outputIndex < length && enc3 >= 0) bytes[outputIndex++] = ((enc2 & 3) << 6) | enc3;
    }
    return bytes;
  }

  function hexEncode(bytes) {
    let output = '';
    for (let index = 0; index < bytes.length; index++) {
      output += (bytes[index] >> 4).toString(16) + (bytes[index] & 15).toString(16);
    }
    return output;
  }

  function hexDecode(text) {
    const clean = String(text);
    const bytes = new Uint8Array(Math.floor(clean.length / 2));
    for (let index = 0; index < bytes.length; index++) {
      bytes[index] = parseInt(clean.substr(index * 2, 2), 16);
    }
    return bytes;
  }

  function latin1Encode(text) {
    const bytes = new Uint8Array(text.length);
    for (let index = 0; index < text.length; index++) {
      bytes[index] = text.charCodeAt(index) & 255;
    }
    return bytes;
  }

  function latin1Decode(bytes) {
    let output = '';
    for (let index = 0; index < bytes.length; index++) {
      output += String.fromCharCode(bytes[index]);
    }
    return output;
  }

  // GraalJS does not always expose TextEncoder/TextDecoder, so encode/decode UTF-8 by hand.
  function utf8Encode(text) {
    const string = String(text);
    const bytes = [];
    for (let index = 0; index < string.length; index++) {
      let codePoint = string.charCodeAt(index);
      if (codePoint >= 0xD800 && codePoint <= 0xDBFF && index + 1 < string.length) {
        const low = string.charCodeAt(index + 1);
        if (low >= 0xDC00 && low <= 0xDFFF) {
          codePoint = 0x10000 + ((codePoint - 0xD800) << 10) + (low - 0xDC00);
          index++;
        }
      }
      if (codePoint < 0x80) {
        bytes.push(codePoint);
      } else if (codePoint < 0x800) {
        bytes.push(0xC0 | (codePoint >> 6), 0x80 | (codePoint & 0x3F));
      } else if (codePoint < 0x10000) {
        bytes.push(0xE0 | (codePoint >> 12), 0x80 | ((codePoint >> 6) & 0x3F), 0x80 | (codePoint & 0x3F));
      } else {
        bytes.push(
          0xF0 | (codePoint >> 18),
          0x80 | ((codePoint >> 12) & 0x3F),
          0x80 | ((codePoint >> 6) & 0x3F),
          0x80 | (codePoint & 0x3F));
      }
    }
    return Uint8Array.from(bytes);
  }

  function utf8Decode(bytes) {
    let output = '';
    let index = 0;
    while (index < bytes.length) {
      const byte0 = bytes[index++];
      if (byte0 < 0x80) {
        output += String.fromCharCode(byte0);
      } else if (byte0 >= 0xC0 && byte0 < 0xE0) {
        const byte1 = bytes[index++] & 0x3F;
        output += String.fromCharCode(((byte0 & 0x1F) << 6) | byte1);
      } else if (byte0 >= 0xE0 && byte0 < 0xF0) {
        const byte1 = bytes[index++] & 0x3F;
        const byte2 = bytes[index++] & 0x3F;
        output += String.fromCharCode(((byte0 & 0x0F) << 12) | (byte1 << 6) | byte2);
      } else {
        const byte1 = bytes[index++] & 0x3F;
        const byte2 = bytes[index++] & 0x3F;
        const byte3 = bytes[index++] & 0x3F;
        let codePoint = ((byte0 & 0x07) << 18) | (byte1 << 12) | (byte2 << 6) | byte3;
        codePoint -= 0x10000;
        output += String.fromCharCode(0xD800 + (codePoint >> 10), 0xDC00 + (codePoint & 0x3FF));
      }
    }
    return output;
  }

  const utf8Encoder = { encode: utf8Encode };

  class Buffer extends Uint8Array {
    static from(value, encoding) {
      if (value instanceof Buffer) {
        const copy = new Buffer(value.length);
        copy.set(value);
        return copy;
      }
      if (value instanceof Uint8Array || Array.isArray(value)) {
        const copy = new Buffer(value.length);
        copy.set(value);
        return copy;
      }
      if (typeof value === 'string') {
        const normalized = (encoding || 'utf8').toLowerCase();
        let bytes;
        if (normalized === 'base64') {
          bytes = base64Decode(value);
        } else if (normalized === 'hex') {
          bytes = hexDecode(value);
        } else if (normalized === 'latin1' || normalized === 'binary' || normalized === 'ascii') {
          bytes = latin1Encode(value);
        } else {
          bytes = utf8Encoder.encode(value);
        }
        const copy = new Buffer(bytes.length);
        copy.set(bytes);
        return copy;
      }
      if (typeof value === 'number') {
        return new Buffer(value);
      }
      if (value && typeof value.length === 'number') {
        const copy = new Buffer(value.length);
        for (let index = 0; index < value.length; index++) {
          copy[index] = value[index] & 255;
        }
        return copy;
      }
      return new Buffer(0);
    }

    static alloc(size, fill) {
      const buffer = new Buffer(size);
      if (fill !== undefined) {
        buffer.fill(typeof fill === 'number' ? fill : 0);
      }
      return buffer;
    }

    static concat(list, totalLength) {
      let total = totalLength;
      if (total === undefined) {
        total = 0;
        for (let index = 0; index < list.length; index++) {
          total += list[index] ? list[index].length : 0;
        }
      }
      const result = new Buffer(total);
      let offset = 0;
      for (let index = 0; index < list.length; index++) {
        const chunk = list[index];
        if (!chunk || !chunk.length) {
          continue;
        }
        const slice = offset + chunk.length > total ? chunk.subarray(0, total - offset) : chunk;
        result.set(slice, offset);
        offset += slice.length;
      }
      return result;
    }

    static isBuffer(value) {
      return value instanceof Buffer;
    }

    static byteLength(value, encoding) {
      if (typeof value === 'string') {
        return Buffer.from(value, encoding).length;
      }
      return value && typeof value.length === 'number' ? value.length : 0;
    }

    toString(encoding, start, end) {
      const view = (start || end !== undefined)
        ? this.subarray(start || 0, end === undefined ? this.length : end)
        : this;
      const normalized = (encoding || 'utf8').toLowerCase();
      if (normalized === 'base64') {
        return base64Encode(view);
      }
      if (normalized === 'hex') {
        return hexEncode(view);
      }
      if (normalized === 'latin1' || normalized === 'binary' || normalized === 'ascii') {
        return latin1Decode(view);
      }
      return utf8Decode(view);
    }

    toJSON() {
      return { type: 'Buffer', data: Array.prototype.slice.call(this) };
    }

    equals(other) {
      if (!other || other.length !== this.length) {
        return false;
      }
      for (let index = 0; index < this.length; index++) {
        if (this[index] !== other[index]) {
          return false;
        }
      }
      return true;
    }
  }

  global.Buffer = Buffer;

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

        // Buffer of raw CDP messages pulled from the host but not yet delivered. They are
        // delivered one per drain() call so the guest fully drains its microtask queue
        // between messages (mirroring Node's pipe reader). Batch-delivering them would run
        // listener-registration .then() continuations too late and drop early events such as
        // the main-world Runtime.executionContextCreated.
        this._inbox = [];

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
          // Fire-and-forget: the response arrives via drain(), like any other CDP message.
          host.webSocketClient().send(this.browserConnectionId, payload);
          // Closing Chromium tears down the CDP connection; Chrome will not answer further.
          // Surface the disconnect (onclose) so the server-side Browser.close() completes and
          // the driver replies to the client instead of waiting forever.
          if (payload.indexOf('"method":"Browser.close"') >= 0) {
            this.close();
          }
          return undefined;
        }

        return host.driverPipe().writeMessage(String(message));
      }

      drain() {
        if (!this.browserConnectionId) {
          return 0;
        }
        if (this._inbox.length === 0) {
          String(host.webSocketClient().drain(this.browserConnectionId, 5) || '').split('').forEach((message) => {
            if (message) {
              this._inbox.push(message);
            }
          });
        }
        if (this._inbox.length === 0) {
          return 0;
        }
        // Deliver exactly one message; the Java pump calls drain() again, and the microtask
        // queue fully drains between calls (each call is its own context evaluation).
        const message = this._inbox.shift();
        if (this.onmessage) {
          this.onmessage(JSON.parse(message));
        }
        return 1;
      }

      close() {
        if (this.__playwright4jClosing) {
          return;
        }
        this.__playwright4jClosing = true;

        const index = activeBrowserPipes.indexOf(this);
        if (index >= 0) {
          activeBrowserPipes.splice(index, 1);
        }

        // Each external teardown step may fail (the socket/process can already be gone after
        // Browser.close). None of them must prevent onclose from firing, otherwise the
        // server-side close never completes and the client hangs.
        if (this.browserConnectionId) {
          try {
            host.webSocketClient().close(this.browserConnectionId);
          } catch (error) {
            // Connection already closed by the browser.
          }
          this.browserConnectionId = undefined;
        }

        if (this.browserProcessId) {
          try {
            host.processLauncher().close(this.browserProcessId);
          } catch (error) {
            // Process already gone.
          }
          this.browserProcessId = undefined;
        }

        if (this.browserChildProcess && !this.browserChildProcess.killed) {
          closeSpawnedProcess(this.browserChildProcess, 0);
        }
        this.browserChildProcess = undefined;

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
