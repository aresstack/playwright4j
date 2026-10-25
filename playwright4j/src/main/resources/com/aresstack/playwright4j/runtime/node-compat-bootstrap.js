(function installPlaywright4JCompatibilityLayer(global) {
  const modules = {};
  const host = global.__playwright4jHost;
  // Node's optional V8 inspector is not available inside GraalJS.
  modules.inspector = { Session: class Session {
    constructor() { throw new Error('Node inspector profiling is unsupported in Playwright4J'); }
  } };
  modules.vm = {
    Script: class Script {
      constructor() { throw new Error('Node vm.Script is unsupported in Playwright4J'); }
    },
    createContext: function () { throw new Error('Node vm contexts are unsupported in Playwright4J'); },
    runInContext: function () { throw new Error('Node vm contexts are unsupported in Playwright4J'); }
  };

  global.global = global;

  // Node 20+ exposes Event and EventTarget as globals. GraalJS does not.
  // Playwright 1.62's bundled eventsource dependency defines classes extending both.
  if (typeof global.Event !== 'function') {
    global.Event = class Event {
      constructor(type, options) {
        this.type = String(type);
        this.bubbles = Boolean(options && options.bubbles);
        this.cancelable = Boolean(options && options.cancelable);
        this.defaultPrevented = false;
      }
      preventDefault() { if (this.cancelable) this.defaultPrevented = true; }
      stopPropagation() {}
      stopImmediatePropagation() {}
    };
  }
  if (typeof global.EventTarget !== 'function') {
    global.EventTarget = class EventTarget {
      constructor() { this._eventListeners = new Map(); }
      addEventListener(type, listener, options) {
        if (!listener) return;
        const key = String(type);
        const listeners = this._eventListeners.get(key) || [];
        if (!listeners.some(entry => entry.listener === listener)) {
          listeners.push({ listener: listener, once: Boolean(options && options.once) });
        }
        this._eventListeners.set(key, listeners);
      }
      removeEventListener(type, listener) {
        const key = String(type);
        const listeners = this._eventListeners.get(key);
        if (listeners) this._eventListeners.set(key, listeners.filter(entry => entry.listener !== listener));
      }
      dispatchEvent(event) {
        for (const entry of (this._eventListeners.get(String(event.type)) || []).slice()) {
          if (entry.once) this.removeEventListener(event.type, entry.listener);
          if (typeof entry.listener === 'function') entry.listener.call(this, event);
          else if (entry.listener && typeof entry.listener.handleEvent === 'function') {
            entry.listener.handleEvent(event);
          }
        }
        return !event.defaultPrevented;
      }
    };
  }

  // Playwright 1.62 bundles its optional Hono HTTP server. Its Request subclass is
  // defined at module load even when the server is not used. GraalJS has no Node
  // global Request; fail explicitly if the optional HTTP server tries to use it.
  if (typeof global.Request !== 'function') {
    global.Request = class Request {
      constructor() { throw new Error('Node fetch Request is unsupported in Playwright4J'); }
    };
  }
  if (typeof global.Response !== 'function') {
    global.Response = class Response {
      constructor() { throw new Error('Node fetch Response is unsupported in Playwright4J'); }
    };
  }

  // The optional MCP eventsource parser subclasses the Web Streams TransformStream.
  // The class must exist during module initialization, even if MCP is not used.
  if (typeof global.TransformStream !== 'function') {
    global.TransformStream = class TransformStream {
      constructor() { throw new Error('Web TransformStream is unsupported in Playwright4J'); }
    };
  }

  // In the normal driver mode System.out is reserved for the length-prefixed protocol, so all
  // console output is routed to the host stderr pipe. In --node-compat mode (cli.js launch-server)
  // console.log/info is real stdout - that is where the ws:// endpoint the caller reads is printed -
  // while console.warn/error stay on stderr, matching Node.
  function consoleWrite(args, toStdout) {
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
      if (toStdout && global.__playwright4jNodeCompat) {
        host.driverPipe().writeOut(text + '\n');
      } else {
        host.driverPipe().writeErr(text + '\n');
      }
    } catch (error) {
      // Diagnostics must never break the run.
    }
  }
  global.console = {
    log: function () { consoleWrite(arguments, true); },
    info: function () { consoleWrite(arguments, true); },
    warn: function () { consoleWrite(arguments, false); },
    error: function () { consoleWrite(arguments, false); },
    debug: function () { consoleWrite(arguments, true); },
    trace: function () { consoleWrite(arguments, false); },
    dir: function () { consoleWrite(arguments, true); },
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

  // V8's Error.captureStackTrace does not exist in GraalJS. ws builds its protocol errors with it
  // (invalid frame, message too big): without it creating the error throws a TypeError instead, and
  // the WebSocket is closed without its close code (1002/1007/1009). commander uses it as well.
  if (typeof Error.captureStackTrace !== 'function') {
    Object.defineProperty(Error, 'captureStackTrace', {
      configurable: true,
      writable: true,
      value: function captureStackTrace(target) {
        if (target === null || (typeof target !== 'object' && typeof target !== 'function')) {
          return;
        }
        // Drop the "Error" line and this function's own frame.
        const frames = String(new Error().stack || '').split('\n').slice(2).join('\n');
        try {
          Object.defineProperty(target, 'stack', {
            configurable: true,
            writable: true,
            value: String(target) + (frames ? '\n' + frames : '')
          });
        } catch (ignored) {
          // A frozen target keeps its stack.
        }
      }
    });
  }

  // An exception thrown by a timer callback or by a listener of a host-driven event (socket data,
  // end, close) would terminate Node.js. Here it is handed to the host's tracker (UnhandledRejections:
  // reported on stderr, or terminating in strict mode) while the other connections keep running.
  function reportUncaughtException(error, origin) {
    const text = origin + ': ' + String(error && error.stack || error);
    try {
      const tracker = global.__playwright4jUnhandledRejections;
      if (tracker) {
        tracker.recordUncaughtException(text);
        return;
      }
    } catch (ignored) {
      // Fall back to stderr below.
    }
    try {
      host.driverPipe().writeErr('[playwright4j] uncaught exception: ' + text + '\n');
    } catch (ignored) {
      // Diagnostics must never break the run.
    }
  }

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
          reportUncaughtException(error, 'timer callback');
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

  // Video recording spawns ffmpeg, which JdkHostProcessLauncher serves with a Java encoder (no
  // native binary). Playwright's registry first checks that the ffmpeg executable is accessible
  // (<registry>/ffmpeg-<rev>/ffmpeg-<platform>) and otherwise demands `playwright install ffmpeg`.
  // Report exactly that registry path as accessible so no ffmpeg download is ever needed.
  function isJavaBackedFfmpeg(path) {
    return /(^|[\\/])ffmpeg-\d+[\\/]ffmpeg-(win64\.exe|linux|mac)$/.test(String(path));
  }

  function hostExists(path) {
    try {
      return host.fileSystem().existsSync(String(path));
    } catch (error) {
      return false;
    }
  }

  // Node fs.constants subset Playwright touches (fs.constants.F_OK etc. during file access).
  const fsConstants = { F_OK: 0, R_OK: 4, W_OK: 2, X_OK: 1, O_RDONLY: 0, O_WRONLY: 1, O_RDWR: 2 };

  function readEncoding(options) {
    if (typeof options === 'string') {
      return options;
    }
    if (options && typeof options === 'object' && options.encoding) {
      return options.encoding;
    }
    return null;
  }

  // Reads a file as raw bytes (Buffer). When an encoding is supplied, decodes to a string,
  // matching Node's fs.readFile/readFileSync semantics (no encoding => Buffer).
  function hostReadFile(path) {
    const base64 = host.fileSystem().readFileBase64(String(path));
    return global.Buffer.from(base64 || '', 'base64');
  }

  function decodeRead(buffer, options) {
    const encoding = readEncoding(options);
    return encoding ? buffer.toString(encoding) : buffer;
  }

  // Writes either text or binary (Buffer/Uint8Array) content, preserving raw bytes. Playwright's
  // HAR/zip code writes Buffers, which must not be stringified.
  function hostWriteFile(path, content) {
    const target = String(path);
    if (content === undefined || content === null) {
      host.fileSystem().writeFile(target, '');
      return;
    }
    if (global.Buffer.isBuffer(content) || content instanceof Uint8Array) {
      host.fileSystem().writeFileBase64(target, global.Buffer.from(content).toString('base64'));
      return;
    }
    host.fileSystem().writeFile(target, String(content));
  }

  // Appends text or binary content to a file (Node fs.appendFile semantics).
  function hostAppendFile(path, content) {
    const target = String(path);
    const buffer = (global.Buffer.isBuffer(content) || content instanceof Uint8Array)
      ? global.Buffer.from(content)
      : global.Buffer.from(content === undefined || content === null ? '' : String(content), 'utf8');
    host.fileSystem().appendFileBase64(target, buffer.toString('base64'));
  }

  function toFileBuffer(data, encoding) {
    if (global.Buffer.isBuffer(data) || data instanceof Uint8Array) {
      return global.Buffer.from(data);
    }
    return global.Buffer.from(data === undefined || data === null ? '' : String(data), typeof encoding === 'string' ? encoding : 'utf8');
  }

  // FileHandle returned by fs.promises.open. Writes are buffered and flushed on close()
  // (write/append mode); reads are served from an in-memory copy. Used e.g. by Chromium tracing.
  function makeFileHandle(path, flags) {
    const target = String(path);
    const mode = flags === undefined || flags === null ? 'r' : String(flags);
    const isAppend = mode.indexOf('a') >= 0;
    const writeChunks = [];
    let wroteAnything = false;
    let readState = null;
    function ensureRead() {
      if (!readState) {
        readState = { buffer: hostReadFile(target), pos: 0 };
      }
      return readState;
    }
    return {
      fd: nextFd++,
      write: async function (data, offsetOrPosition, lengthOrEncoding) {
        let buffer;
        if (typeof data === 'string') {
          buffer = toFileBuffer(data, lengthOrEncoding);
        } else {
          const source = global.Buffer.from(data);
          const offset = typeof offsetOrPosition === 'number' ? offsetOrPosition : 0;
          const length = typeof lengthOrEncoding === 'number' ? lengthOrEncoding : source.length - offset;
          buffer = global.Buffer.from(source.subarray(offset, offset + length));
        }
        writeChunks.push(buffer);
        wroteAnything = true;
        return { bytesWritten: buffer.length, buffer: data };
      },
      writeFile: async function (data, options) {
        writeChunks.push(toFileBuffer(data, options && options.encoding));
        wroteAnything = true;
        return undefined;
      },
      appendFile: async function (data) {
        writeChunks.push(toFileBuffer(data));
        wroteAnything = true;
        return undefined;
      },
      read: async function (buffer, offset, length, position) {
        const state = ensureRead();
        const start = (position === null || position === undefined) ? state.pos : position;
        let count = 0;
        while (count < length && start + count < state.buffer.length) {
          buffer[offset + count] = state.buffer[start + count];
          count++;
        }
        if (position === null || position === undefined) {
          state.pos = start + count;
        }
        return { bytesRead: count, buffer: buffer };
      },
      readFile: async function (options) {
        return decodeRead(hostReadFile(target), options);
      },
      stat: async function () {
        return hostStat(target);
      },
      truncate: async function () {
        writeChunks.length = 0;
        wroteAnything = true;
        return undefined;
      },
      sync: async function () { return undefined; },
      datasync: async function () { return undefined; },
      createReadStream: function () { return modules.fs.createReadStream(target); },
      createWriteStream: function () { return modules.fs.createWriteStream(target); },
      close: async function () {
        if (wroteAnything) {
          const all = writeChunks.length > 0 ? global.Buffer.concat(writeChunks) : global.Buffer.alloc(0);
          if (isAppend) {
            host.fileSystem().appendFileBase64(target, all.toString('base64'));
          } else {
            host.fileSystem().writeFileBase64(target, all.toString('base64'));
          }
        }
        return undefined;
      }
    };
  }

  function enoent(operation, path) {
    const error = new Error('ENOENT: no such file or directory, ' + operation + " '" + String(path) + "'");
    error.code = 'ENOENT';
    error.errno = -2;
    error.path = String(path);
    return error;
  }

  // Converts an fs.utimes time argument to epoch milliseconds. Node accepts a Date, a numeric
  // seconds-since-epoch value, or a parseable string.
  function toEpochMillis(value) {
    if (value instanceof Date) {
      return value.getTime();
    }
    if (typeof value === 'number') {
      return Math.round(value * 1000);
    }
    const parsed = Date.parse(String(value));
    return isNaN(parsed) ? Date.now() : parsed;
  }

  function hostStat(path) {
    const text = String(path);
    if (!hostExists(text)) {
      throw enoent('stat', text);
    }
    const isDir = !!host.fileSystem().isDirectorySync(text);
    const size = Number(host.fileSystem().sizeBytes(text)) || 0;
    const mtimeMs = Number(host.fileSystem().lastModifiedMillis(text)) || 0;
    return {
      size: size,
      mode: isDir ? 16877 : 33188,
      mtimeMs: mtimeMs,
      ctimeMs: mtimeMs,
      atimeMs: mtimeMs,
      birthtimeMs: mtimeMs,
      mtime: new Date(mtimeMs),
      ctime: new Date(mtimeMs),
      atime: new Date(mtimeMs),
      birthtime: new Date(mtimeMs),
      isFile: function () { return !isDir; },
      isDirectory: function () { return isDir; },
      isSymbolicLink: function () { return false; },
      isBlockDevice: function () { return false; },
      isCharacterDevice: function () { return false; },
      isFIFO: function () { return false; },
      isSocket: function () { return false; }
    };
  }

  function hostRealpath(path) {
    const text = String(path);
    if (!hostExists(text)) {
      throw enoent('realpath', text);
    }
    return text;
  }

  const realpathSync = function (path) {
    return hostRealpath(path);
  };
  realpathSync.native = realpathSync;

  // File-descriptor table for fd-based fs ops (fs.open/read/fstat/close). The whole file is read
  // into memory on open and random-access reads are served from it. Used by the zip reader
  // (yauzl) for HAR zip replay; sizes here are small (HAR archives).
  const fdTable = {};
  let nextFd = 3;

  function fdOpen(path) {
    const buffer = hostReadFile(path);
    const fd = nextFd++;
    fdTable[fd] = { buffer: buffer, position: 0, path: String(path) };
    return fd;
  }

  function fdRead(fd, buffer, offset, length, position) {
    const entry = fdTable[fd];
    if (!entry) {
      throw enoent('read', 'fd:' + fd);
    }
    const start = (position === null || position === undefined) ? entry.position : position;
    let count = 0;
    while (count < length && start + count < entry.buffer.length) {
      buffer[offset + count] = entry.buffer[start + count];
      count++;
    }
    if (position === null || position === undefined) {
      entry.position = start + count;
    }
    return count;
  }

  function fdStat(fd) {
    const entry = fdTable[fd];
    if (!entry) {
      throw enoent('fstat', 'fd:' + fd);
    }
    return {
      size: entry.buffer.length,
      mode: 33188,
      mtimeMs: 0,
      mtime: new Date(0),
      isFile: function () { return true; },
      isDirectory: function () { return false; },
      isSymbolicLink: function () { return false; }
    };
  }

  modules.fs = {
    constants: fsConstants,
    openSync: function (path) {
      return fdOpen(path);
    },
    open: function (path, flags, mode, callback) {
      callback = typeof flags === 'function' ? flags : (typeof mode === 'function' ? mode : callback);
      Promise.resolve().then(function () {
        try {
          callback(null, fdOpen(path));
        } catch (error) {
          callback(error);
        }
      });
    },
    readSync: function (fd, buffer, offset, length, position) {
      return fdRead(fd, buffer, offset || 0, length === undefined ? buffer.length : length, position);
    },
    read: function (fd, buffer, offset, length, position, callback) {
      Promise.resolve().then(function () {
        try {
          const bytesRead = fdRead(fd, buffer, offset || 0, length === undefined ? buffer.length : length, position);
          callback(null, bytesRead, buffer);
        } catch (error) {
          callback(error);
        }
      });
    },
    fstatSync: function (fd) {
      return fdStat(fd);
    },
    fstat: function (fd, options, callback) {
      callback = typeof options === 'function' ? options : callback;
      Promise.resolve().then(function () {
        try {
          callback(null, fdStat(fd));
        } catch (error) {
          callback(error);
        }
      });
    },
    closeSync: function (fd) {
      delete fdTable[fd];
    },
    close: function (fd, callback) {
      delete fdTable[fd];
      if (callback) {
        Promise.resolve().then(function () { callback(null); });
      }
    },
    existsSync: function (path) {
      return hostExists(path);
    },
    readFileSync: function (path, options) {
      return decodeRead(hostReadFile(path), options);
    },
    accessSync: function (path) {
      if (!hostExists(path) && !isJavaBackedFfmpeg(path)) {
        throw enoent('access', path);
      }
    },
    statSync: function (path) {
      return hostStat(path);
    },
    lstatSync: function (path) {
      return hostStat(path);
    },
    realpathSync: realpathSync,
    writeFileSync: function (path, content) {
      hostWriteFile(path, content);
    },
    appendFileSync: function (path, content) {
      hostAppendFile(path, content);
    },
    utimesSync: function (path, atime, mtime) {
      host.fileSystem().setLastModifiedMillis(String(path), toEpochMillis(mtime));
    },
    utimes: function (path, atime, mtime, callback) {
      Promise.resolve().then(function () {
        try {
          host.fileSystem().setLastModifiedMillis(String(path), toEpochMillis(mtime));
          if (callback) { callback(null); }
        } catch (error) {
          if (callback) { callback(error); }
        }
      });
    },
    appendFile: function (path, content, options, callback) {
      callback = typeof options === 'function' ? options : callback;
      Promise.resolve().then(function () {
        try {
          hostAppendFile(path, content);
          if (callback) { callback(null); }
        } catch (error) {
          if (callback) { callback(error); }
        }
      });
    },
    mkdirSync: function (path) {
      host.fileSystem().createDirectories(String(path));
      return undefined;
    },
    mkdtempSync: function (prefix) {
      return host.fileSystem().createTempDirectory(String(prefix));
    },
    rmSync: function (path, options) {
      if (path !== undefined) {
        if (options && options.recursive) {
          host.fileSystem().deleteRecursively(String(path));
        } else {
          host.fileSystem().deleteFile(String(path));
        }
      }
      return undefined;
    },
    rmdirSync: function (path, options) {
      if (path !== undefined) {
        host.fileSystem().deleteRecursively(String(path));
      }
      return undefined;
    },
    rm: function (path, options, callback) {
      if (typeof options === 'function') {
        callback = options;
        options = undefined;
      }
      Promise.resolve().then(function () {
        try {
          if (options && options.recursive) {
            host.fileSystem().deleteRecursively(String(path));
          } else {
            host.fileSystem().deleteFile(String(path));
          }
          if (callback) { callback(null); }
        } catch (error) {
          if (callback) { callback(error); }
        }
      });
    },
    unlinkSync: function (path) {
      host.fileSystem().deleteFile(String(path));
      return undefined;
    },
    copyFileSync: function (source, destination) {
      host.fileSystem().copyFile(String(source), String(destination));
      return undefined;
    },
    renameSync: function (source, destination) {
      host.fileSystem().rename(String(source), String(destination));
      return undefined;
    },
    unlink: function (path, callback) {
      Promise.resolve().then(function () {
        try {
          host.fileSystem().deleteFile(String(path));
          if (callback) { callback(null); }
        } catch (error) {
          if (callback) { callback(error); }
        }
      });
    },
    rename: function (source, destination, callback) {
      Promise.resolve().then(function () {
        try {
          host.fileSystem().rename(String(source), String(destination));
          if (callback) { callback(null); }
        } catch (error) {
          if (callback) { callback(error); }
        }
      });
    },
    stat: function (path, callback) {
      Promise.resolve().then(function () {
        try {
          callback(null, hostStat(path));
        } catch (error) {
          callback(error);
        }
      });
    },
    promises: {
      readFile: async function (path, options) {
        return decodeRead(hostReadFile(path), options);
      },
      mkdtemp: async function (prefix) {
        return host.fileSystem().createTempDirectory(String(prefix));
      },
      stat: async function (path) {
        return hostStat(path);
      },
      lstat: async function (path) {
        return hostStat(path);
      },
      realpath: async function (path) {
        return hostRealpath(path);
      },
      writeFile: async function (path, content) {
        hostWriteFile(path, content);
        return undefined;
      },
      appendFile: async function (path, content) {
        hostAppendFile(path, content);
        return undefined;
      },
      utimes: async function (path, atime, mtime) {
        host.fileSystem().setLastModifiedMillis(String(path), toEpochMillis(mtime));
        return undefined;
      },
      open: async function (path, flags) {
        return makeFileHandle(path, flags);
      },
      mkdir: async function (path) {
        host.fileSystem().createDirectories(String(path));
        return undefined;
      },
      rm: async function (path, options) {
        if (path !== undefined) {
          if (options && options.recursive) {
            host.fileSystem().deleteRecursively(String(path));
          } else {
            host.fileSystem().deleteFile(String(path));
          }
        }
        return undefined;
      },
      rmdir: async function (path) {
        if (path !== undefined) {
          host.fileSystem().deleteRecursively(String(path));
        }
        return undefined;
      },
      unlink: async function (path) {
        host.fileSystem().deleteFile(String(path));
        return undefined;
      },
      copyFile: async function (source, destination) {
        host.fileSystem().copyFile(String(source), String(destination));
        return undefined;
      },
      rename: async function (source, destination) {
        host.fileSystem().rename(String(source), String(destination));
        return undefined;
      },
      access: async function (path) {
        if (!hostExists(path)) {
          throw enoent('access', path);
        }
      }
    }
  };

  // Playwright 1.62 bundles code that imports the standalone Node fs/promises module.
  // Expose the existing filesystem promises implementation through that canonical module ID.
  modules['fs/promises'] = modules.fs.promises;

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
  // Chokidar's glob-parent requires path.posix.dirname in Playwright 1.62.
  modules.path.posix = modules.path;
  modules.path.win32 = modules.path;

  modules.os = {
    type: function () {
      const platform = host.environment().platform();
      return platform === 'win32' ? 'Windows_NT' : platform === 'darwin' ? 'Darwin' : 'Linux';
    },
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

  // Child processes by their pid, so that Playwright's force kill (process.kill(-pid, 'SIGKILL'),
  // or spawnSync('taskkill /pid <pid> /T /F') on Windows) reaches the host process it means.
  // The pid is the real OS pid where the host has one, otherwise a stand-in unique to this runtime.
  const childProcessesByPid = {};
  let nextStandInPid = 4000000;

  function registerChildProcess(child, processId) {
    let pid = -1;
    try {
      pid = Number(host.processLauncher().pid(String(processId)));
    } catch (error) {
      // A launcher without pids: use a stand-in.
    }
    child.pid = pid > 0 ? pid : nextStandInPid++;
    childProcessesByPid[child.pid] = child;
  }

  function unregisterChildProcess(child) {
    if (child && childProcessesByPid[child.pid] === child) {
      delete childProcessesByPid[child.pid];
    }
  }

  // Node's signal semantics on top of the host: SIGKILL ends the process tree at once, any other
  // signal asks the process to terminate and kills its tree only after a grace period.
  function signalHostProcess(processId, signal) {
    const launcher = host.processLauncher();
    if (signal === 'SIGKILL' || signal === 9) {
      launcher.kill(String(processId));
    } else {
      launcher.close(String(processId));
    }
  }

  function noSuchProcess(syscall, pid) {
    const error = new Error(syscall + ' ESRCH');
    error.code = 'ESRCH';
    error.errno = -3;
    error.syscall = syscall;
    error.pid = pid;
    return error;
  }

  // process.kill: only the children this runtime spawned are reachable (a negative pid, Node's
  // process-group form, means the same child's tree). Signal 0 tests for existence, as in Node.
  function killChildProcessByPid(pid, signal) {
    const child = childProcessesByPid[Math.abs(Number(pid))];
    if (!child) {
      throw noSuchProcess('kill', pid);
    }
    if (signal === 0) {
      return true;
    }
    signalHostProcess(child.__playwright4jProcessId, signal === undefined ? 'SIGTERM' : signal);
    child.killed = true;
    return true;
  }

  // Playwright's Windows force kill runs `taskkill /pid <pid> /T /F` through spawnSync with a
  // shell. Recognise it and end the host process tree; no taskkill.exe is started. Returns null
  // for any other command.
  function taskkillFor(command, args) {
    const words = [String(command || '')].concat((args || []).map(String)).join(' ').trim().split(/\s+/);
    if (words.length === 0 || !/^taskkill(\.exe)?$/i.test(words[0].replace(/^.*[\\/]/, ''))) {
      return null;
    }
    const pids = [];
    let force = false;
    for (let index = 1; index < words.length; index++) {
      const word = words[index].toLowerCase();
      if (word === '/pid' && index + 1 < words.length) {
        pids.push(Number(words[++index]));
      } else if (word === '/f') {
        force = true;
      }
    }
    const stdout = [];
    const stderr = [];
    pids.forEach(function (pid) {
      const child = childProcessesByPid[pid];
      if (!child) {
        stderr.push('ERROR: The process "' + pid + '" not found.');
        return;
      }
      signalHostProcess(child.__playwright4jProcessId, force ? 'SIGKILL' : 'SIGTERM');
      stdout.push('SUCCESS: Sent termination signal to the process with PID ' + pid + '.');
    });
    return {
      pid: 0,
      status: stderr.length > 0 ? 128 : 0,
      signal: null,
      output: [null, global.Buffer.from(stdout.join('\r\n')), global.Buffer.from(stderr.join('\r\n'))],
      stdout: global.Buffer.from(stdout.join('\r\n')),
      stderr: global.Buffer.from(stderr.join('\r\n'))
    };
  }

  function closeSpawnedProcess(child, exitCode) {
    if (!child || child.__playwright4jClosed) {
      return;
    }
    child.__playwright4jClosed = true;
    unregisterChildProcess(child);
    delete browserProcessesById[child.__playwright4jProcessId];
    child.killed = true;
    const code = typeof exitCode === 'number' ? exitCode : 0;
    child.emit('exit', code, null);
    child.emit('close', code, null);
  }

  // Chromium processes by host id; the host reports their real exit (see the drain below).
  const browserProcessesById = {};

  function createSpawnedProcess(processId, endpoint) {
    const child = new EventEmitter();
    child.killed = false;
    child.__playwright4jClosed = false;
    child.__playwright4jProcessId = processId;
    registerChildProcess(child, processId);
    browserProcessesById[processId] = child;
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
    child.kill = function (signal) {
      if (!child.__playwright4jClosed) {
        try {
          signalHostProcess(processId, signal);
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
      // Playwright's waitForReadyState waits for Chromium's "DevTools listening on ..." line
      // on stderr when an explicit --remote-debugging-port is supplied. Our launcher already
      // discovered the endpoint, so surface it as that line once the consumer is listening.
      if (endpoint) {
        child.stderr.emit('data', 'DevTools listening on ' + endpoint + '\n');
      }
    });
    return child;
  }

  // General (non-Chromium) subprocesses, e.g. the ffmpeg video encoder. Input is written to the
  // child's stdin; stdout/stderr/exit arrive via __playwright4jDrainProcesses on the JS thread.
  const PROC_UNIT = '';
  const generalProcessesById = {};

  function createGeneralProcess(processId) {
    const child = new EventEmitter();
    child.killed = false;
    child.__playwright4jProcessId = processId;
    registerChildProcess(child, processId);

    const stdin = new Writable();
    stdin._write = function (chunk, encoding, callback) {
      const buffer = global.Buffer.isBuffer(chunk)
        ? chunk
        : global.Buffer.from(String(chunk), typeof encoding === 'string' ? encoding : 'utf8');
      host.processLauncher().writeStdin(processId, buffer.toString('base64'));
      callback();
    };
    stdin._final = function (callback) {
      host.processLauncher().endStdin(processId);
      callback();
    };

    const stdout = new Readable();
    const stderr = new Readable();
    child.stdin = stdin;
    child.stdout = stdout;
    child.stderr = stderr;
    child.stdio = [stdin, stdout, stderr];
    // Node semantics: the 'exit' event follows when the host reports the real exit.
    child.kill = function (signal) {
      if (!child.killed) {
        child.killed = true;
        try {
          signalHostProcess(processId, signal);
        } catch (error) {
          // Already gone.
        }
      }
      return true;
    };
    child.ref = function () { return child; };
    child.unref = function () { return child; };

    generalProcessesById[processId] = { child: child, stdout: stdout, stderr: stderr };
    return child;
  }

  global.__playwright4jDrainProcesses = function () {
    const batch = String(host.processLauncher().drainProcessEvents() || '');
    if (batch.length === 0) {
      return 0;
    }
    const lines = batch.split('\n');
    let handled = 0;
    for (let index = 0; index < lines.length; index++) {
      const parts = lines[index].split(PROC_UNIT);
      const entry = generalProcessesById[parts[1]];
      if (!entry) {
        if (parts[0] === 'exit' && browserProcessesById[parts[1]]) {
          // A Chromium process ended (e.g. after a force kill) before its CDP connection closed.
          closeSpawnedProcess(browserProcessesById[parts[1]], parseInt(parts[2], 10));
          handled++;
        }
        continue;
      }
      if (parts[0] === 'data') {
        const target = parts[2] === 'stderr' ? entry.stderr : entry.stdout;
        target.push(global.Buffer.from(parts[3] || '', 'base64'));
      } else if (parts[0] === 'exit') {
        const code = parseInt(parts[2], 10);
        entry.stdout.push(null);
        entry.stderr.push(null);
        entry.child.killed = true;
        unregisterChildProcess(entry.child);
        entry.child.emit('exit', code, null);
        entry.child.emit('close', code, null);
        delete generalProcessesById[parts[1]];
      }
      handled++;
    }
    return handled;
  };

  // Recognises a non-Chromium browser launch so it can be rejected fast (see spawn below).
  // WebKit and Firefox are identified by their pipe-transport flags; the engine name is taken
  // from the browser binary's directory (e.g. .../webkit-2272/Playwright.exe) for a clear message.
  function detectUnsupportedBrowserEngine(command, argList) {
    const hasWebKitPipe = argList.indexOf('--inspector-pipe') >= 0;
    const hasFirefoxPipe = argList.indexOf('-juggler-pipe') >= 0 || argList.indexOf('--juggler-pipe') >= 0;
    if (!hasWebKitPipe && !hasFirefoxPipe) {
      return null;
    }
    const normalized = String(command || '').replace(/\\/g, '/').toLowerCase();
    if (normalized.indexOf('/webkit-') >= 0 || hasWebKitPipe) {
      return 'webkit';
    }
    if (normalized.indexOf('/firefox-') >= 0 || hasFirefoxPipe) {
      return 'firefox';
    }
    return 'non-chromium';
  }

  // Playwright's BiDi Firefox (bidiFirefox.js) omits two flags its Juggler Firefox always passes.
  // Without -wait-for-browser a headed firefox.exe on Windows is only the launcher process: it
  // hands off to the real browser and exits with code 0, which Playwright reports as "Failed to
  // launch the browser process" while the real browser keeps running and holds the profile lock.
  // Without -no-remote a headed start may be handed to an already running Firefox instead.
  function withFirefoxInstanceFlags(argList) {
    const result = argList.slice();
    const has = function (flag) {
      return result.indexOf('-' + flag) >= 0 || result.indexOf('--' + flag) >= 0;
    };
    if (!has('no-remote')) {
      result.unshift('-no-remote');
    }
    if (!has('headless') && !has('wait-for-browser')) {
      result.unshift('-wait-for-browser');
    }
    return result;
  }

  // Node's spawn semantics: options.env, when given, is the child's complete environment (it
  // replaces the inherited one); entries whose value is undefined are dropped. Playwright passes
  // either the launch option `env` (setEnv in the Java API) or process.env, amended per browser
  // (e.g. MOZ_CRASHREPORTER for Firefox). Returns null to inherit the host's environment.
  // The values are handed to the host only and never logged (they may carry secrets).
  function environmentFor(options) {
    if (!options || !options.env) {
      return null;
    }
    const entries = [];
    Object.keys(options.env).forEach(function (name) {
      const value = options.env[name];
      if (value !== undefined && value !== null) {
        entries.push(String(name) + '=' + String(value));
      }
    });
    return entries.join(ARGUMENT_SEPARATOR);
  }

  modules.child_process = {
    spawn: function (command, args, options) {
      const argList = (args || []).map(function (value) { return String(value); });
      const joinedArguments = argList.join(ARGUMENT_SEPARATOR);
      const workingDirectory = options && options.cwd ? String(options.cwd) : global.process.cwd();
      const environment = environmentFor(options);

      // Firefox over WebDriver BiDi (Playwright's moz-firefox channel) also passes
      // --remote-debugging-port, but together with a -profile/--profile directory. It is run as a
      // general subprocess: Playwright itself parses "WebDriver BiDi listening on ws://..." from
      // stderr and the exit watcher reports the real process exit.
      const isFirefoxBiDi = argList.some(function (value) {
        return value.indexOf('--remote-debugging-port') === 0;
      }) && (argList.indexOf('-profile') >= 0 || argList.indexOf('--profile') >= 0);
      if (isFirefoxBiDi) {
        const firefoxArguments = withFirefoxInstanceFlags(argList).join(ARGUMENT_SEPARATOR);
        const firefoxId = String(host.processLauncher().spawnProcessWithEnvironment(String(command), firefoxArguments, workingDirectory, environment));
        return createGeneralProcess(firefoxId);
      }

      // A Chromium launch is identified by its remote-debugging transport flag; everything else
      // (notably the ffmpeg video encoder) is a general subprocess fed via stdin.
      const isChromium = argList.some(function (value) {
        return value.indexOf('--remote-debugging') === 0;
      });
      if (!isChromium) {
        // WebKit (--inspector-pipe) and Firefox (-juggler-pipe) drive their protocol over fd 3/4
        // pipes that this Chromium-only runtime does not wire up. Spawning such a browser succeeds
        // but its transport handshake never completes, so Playwright's launch blocks on its
        // 180s timeout (a full-sweep hang). Reject fast with a clear error instead — the launch
        // promise then rejects promptly and no doomed process is left running.
        const unsupportedEngine = detectUnsupportedBrowserEngine(command, argList);
        if (unsupportedEngine) {
          throw new Error('Unsupported browser engine: ' + unsupportedEngine
            + '. playwright4j runs a Chromium-only runtime; only the chromium browser'
            + ' (chrome / msedge channels) is available.');
        }
        const generalId = String(host.processLauncher().spawnProcessWithEnvironment(String(command), joinedArguments, workingDirectory, environment));
        return createGeneralProcess(generalId);
      }

      // launchChromium throws on failure; let it propagate so Playwright's launch promise
      // rejects with a real error instead of hanging.
      const result = String(host.processLauncher().launchChromiumWithEnvironment(String(command), joinedArguments, workingDirectory, environment));
      const separatorIndex = result.indexOf(ARGUMENT_SEPARATOR);
      const processId = separatorIndex < 0 ? result : result.substring(0, separatorIndex);
      const endpoint = separatorIndex < 0 ? '' : result.substring(separatorIndex + 1);
      return createSpawnedProcess(processId, endpoint);
    },
    execFile: unsupported('child_process.execFile'),
    execFileSync: unsupported('child_process.execFileSync'),
    spawnSync: function (command, args) {
      const taskkill = taskkillFor(command, Array.isArray(args) ? args : []);
      if (taskkill) {
        return taskkill;
      }
      return { status: 0, stdout: global.Buffer.from(''), stderr: global.Buffer.from('') };
    }
  };

  // EventEmitter is a function constructor (not an ES6 class) so bundled CommonJS libraries can
  // inherit it via `EventEmitter.call(this)` (e.g. yauzl's zip reader). ES6 classes forbid being
  // invoked without `new`; a function constructor supports `new EventEmitter()`, `.call(this)`,
  // and `class X extends EventEmitter` alike. Listener registry is lazily initialized so libs
  // that inherit via util.inherits without calling the constructor still work.
  function EventEmitter() {
    if (!this.listenersByName) {
      this.listenersByName = {};
    }
  }

  // Use a lexical helper rather than a prototype method. Playwright 1.62 assigns
  // an unrelated __listeners field on some emitters, shadowing the old helper.
  function eventEmitterRegistry(emitter) {
    if (!emitter.listenersByName) {
      emitter.listenersByName = {};
    }
    return emitter.listenersByName;
  }

  EventEmitter.prototype.on = function (name, listener) {
    const registry = eventEmitterRegistry(this);
    registry[name] = registry[name] || [];
    registry[name].push(listener);
    return this;
  };

  // Like Node, addListener/removeListener are the very same functions as on/off. Playwright's
  // BidiSession and CDPSession assign `this.off = super.removeListener` (and `this.on =
  // super.on`); delegating via this.off/this.on would then recurse until the stack overflows,
  // which broke BrowserContext.close() for Firefox over WebDriver BiDi.
  EventEmitter.prototype.addListener = EventEmitter.prototype.on;

  EventEmitter.prototype.prependListener = function (name, listener) {
    const registry = eventEmitterRegistry(this);
    registry[name] = registry[name] || [];
    registry[name].unshift(listener);
    return this;
  };

  EventEmitter.prototype.once = function (name, listener) {
    const self = this;
    function onceListener() {
      self.off(name, onceListener);
      return listener.apply(this, arguments);
    }
    onceListener.__originalListener = listener;
    return this.on(name, onceListener);
  };

  EventEmitter.prototype.prependOnceListener = function (name, listener) {
    const self = this;
    function onceListener() {
      self.off(name, onceListener);
      return listener.apply(this, arguments);
    }
    onceListener.__originalListener = listener;
    return this.prependListener(name, onceListener);
  };

  EventEmitter.prototype.off = function (name, listener) {
    const registry = eventEmitterRegistry(this);
    const listeners = registry[name] || [];
    registry[name] = listeners.filter(function (candidate) {
      return candidate !== listener && candidate.__originalListener !== listener;
    });
    return this;
  };

  EventEmitter.prototype.removeListener = EventEmitter.prototype.off;

  EventEmitter.prototype.removeAllListeners = function (name) {
    const registry = eventEmitterRegistry(this);
    if (name === undefined) {
      this.listenersByName = {};
    } else {
      delete registry[name];
    }
    return this;
  };

  EventEmitter.prototype.setMaxListeners = function (value) {
    this.maxListeners = value;
    return this;
  };

  EventEmitter.prototype.getMaxListeners = function () {
    return this.maxListeners || 0;
  };

  EventEmitter.prototype.eventNames = function () {
    return Object.keys(eventEmitterRegistry(this));
  };

  EventEmitter.prototype.listeners = function (name) {
    return (eventEmitterRegistry(this)[name] || []).slice();
  };

  EventEmitter.prototype.rawListeners = function (name) {
    return (eventEmitterRegistry(this)[name] || []).slice();
  };

  EventEmitter.prototype.listenerCount = function (name) {
    return (eventEmitterRegistry(this)[name] || []).length;
  };

  EventEmitter.prototype.emit = function (name) {
    const self = this;
    const args = Array.prototype.slice.call(arguments, 1);
    const listeners = (eventEmitterRegistry(this)[name] || []).slice();
    // Node throws if an 'error' event has no listeners; surface that instead of swallowing.
    if (listeners.length === 0 && name === 'error') {
      const err = args[0];
      throw (err instanceof Error) ? err : new Error('Unhandled "error" event');
    }
    // Node invokes listeners with `this` bound to the emitter. Libraries rely on it: the bundled
    // ws socketOnData does `this[kWebSocket]._receiver.write(chunk)`, so a null/global `this` would
    // silently drop every inbound WebSocket frame (launch-server never sees protocol messages).
    listeners.forEach(function (listener) {
      listener.apply(self, args);
    });
    return listeners.length > 0;
  };

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

  // The debug package bundled with Playwright 1.62 formats DEBUG=pw:* output with
  // util.formatWithOptions(inspectOptions, ...args); the options only style the output.
  modules.util.formatWithOptions = function (inspectOptions) {
    return modules.util.format.apply(modules.util, Array.prototype.slice.call(arguments, 1));
  };

  // ws inside Playwright 1.62's bundled utilities expects Node's util.types API.
  modules.util.types = {
    isUint8Array: function (value) { return value instanceof Uint8Array; }
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

  function createHostHash(algorithm) {
    const chunks = [];
    return {
      update: function (data, encoding) {
        chunks.push(global.Buffer.from(data, encoding));
        return this;
      },
      digest: function (encoding) {
        const base64 = chunks.length > 0 ? global.Buffer.concat(chunks).toString('base64') : '';
        const hex = host.digestHex(String(algorithm), base64);
        if (encoding === 'hex') {
          return hex;
        }
        if (!encoding) {
          // Node returns a Buffer of the raw digest bytes when no encoding is given.
          return global.Buffer.from(hex, 'hex');
        }
        return global.Buffer.from(hex, 'hex').toString(encoding);
      }
    };
  }

  function secureRandomBytes(size) {
    const length = size | 0;
    if (length <= 0) {
      return global.Buffer.alloc(0);
    }
    return global.Buffer.from(String(host.crypto().randomBytes(length)), 'base64');
  }

  modules.crypto = {
    // Playwright derives secrets from these (launch-server ws path, GUIDs), so they come from the
    // host's SecureRandom; Math.random is predictable.
    randomBytes: function (size) {
      return secureRandomBytes(size);
    },
    createHash: function (algorithm) {
      return createHostHash(algorithm);
    },
    randomUUID: function () {
      const bytes = secureRandomBytes(16);
      bytes[6] = (bytes[6] & 0x0f) | 0x40;
      bytes[8] = (bytes[8] & 0x3f) | 0x80;
      const hex = bytes.toString('hex');
      return hex.substring(0, 8) + '-' + hex.substring(8, 12) + '-' + hex.substring(12, 16)
        + '-' + hex.substring(16, 20) + '-' + hex.substring(20);
    },
    // RSA key-pair generation + RSA-SHA256 signing, used by Playwright's
    // generateSelfSignedCertificate (client-certificate dummy server cert). Only RSA + the
    // export forms that function uses (public pkcs1/der, private pkcs1/pem) are supported.
    generateKeyPairSync: function (type, options) {
      if (String(type) !== 'rsa') {
        throw new Error('crypto.generateKeyPairSync: only RSA is supported');
      }
      const bits = options && typeof options.modulusLength === 'number' ? options.modulusLength : 2048;
      const parts = String(host.crypto().generateRsaKeyPair(bits)).split(String.fromCharCode(30));
      const publicPkcs1Der = global.Buffer.from(parts[1], 'base64');
      const privatePkcs1Pem = parts[2];
      return {
        publicKey: createKeyObject(null, publicPkcs1Der, null),
        privateKey: createKeyObject(parts[0], null, privatePkcs1Pem)
      };
    },
    sign: function (algorithm, data, key) {
      const keyId = key && key.__pw4jKeyId;
      if (!keyId) {
        throw new Error('crypto.sign requires a private KeyObject from generateKeyPairSync');
      }
      const base64 = host.crypto().signSha256(keyId, toInputBuffer(data).toString('base64'));
      return global.Buffer.from(base64, 'base64');
    }
  };

  function createKeyObject(keyId, publicPkcs1Der, privatePkcs1Pem) {
    return {
      __pw4jKeyId: keyId,
      asymmetricKeyType: 'rsa',
      export: function (options) {
        const format = options && options.format;
        if (publicPkcs1Der) {
          return format === 'pem'
            ? derToPem('RSA PUBLIC KEY', publicPkcs1Der)
            : publicPkcs1Der;
        }
        if (privatePkcs1Pem) {
          return format === 'der'
            ? pemBodyToBuffer(privatePkcs1Pem)
            : privatePkcs1Pem;
        }
        return null;
      }
    };
  }

  function derToPem(label, der) {
    const base64 = global.Buffer.from(der).toString('base64');
    const lines = base64.match(/.{1,64}/g) || [base64];
    return '-----BEGIN ' + label + '-----\n' + lines.join('\n') + '\n-----END ' + label + '-----\n';
  }

  function pemBodyToBuffer(pem) {
    const body = String(pem).replace(/-----BEGIN [^-]+-----/g, '').replace(/-----END [^-]+-----/g, '').replace(/\s+/g, '');
    return global.Buffer.from(body, 'base64');
  }

  function toInputBuffer(value) {
    if (global.Buffer.isBuffer(value)) {
      return value;
    }
    if (value instanceof Uint8Array || Array.isArray(value)) {
      return global.Buffer.from(value);
    }
    return global.Buffer.from(String(value), 'utf8');
  }

  function zlibLevel(options) {
    return options && typeof options.level === 'number' ? options.level : -1;
  }

  function zlibDeflateRaw(value, options) {
    return global.Buffer.from(host.deflateRaw(toInputBuffer(value).toString('base64'), zlibLevel(options)), 'base64');
  }

  function zlibInflateRaw(value) {
    return global.Buffer.from(host.inflateRaw(toInputBuffer(value).toString('base64')), 'base64');
  }

  function zlibGzip(value) {
    return global.Buffer.from(host.gzip(toInputBuffer(value).toString('base64')), 'base64');
  }

  function zlibGunzip(value) {
    return global.Buffer.from(host.gunzip(toInputBuffer(value).toString('base64')), 'base64');
  }

  // Node-style async wrapper: (input, [options], callback) with callback(error, result).
  function zlibAsync(compute) {
    return function (value, options, callback) {
      if (typeof options === 'function') {
        callback = options;
        options = undefined;
      }
      Promise.resolve().then(function () {
        try {
          const result = compute(value, options);
          if (callback) { callback(null, result); }
        } catch (error) {
          if (callback) { callback(error); }
        }
      });
    };
  }

  modules.zlib = {
    deflateRawSync: function (value, options) { return zlibDeflateRaw(value, options); },
    inflateRawSync: function (value) { return zlibInflateRaw(value); },
    deflateRaw: zlibAsync(zlibDeflateRaw),
    inflateRaw: zlibAsync(zlibInflateRaw),
    gzipSync: function (value) { return zlibGzip(value); },
    gunzipSync: function (value) { return zlibGunzip(value); },
    gzip: zlibAsync(zlibGzip),
    gunzip: zlibAsync(zlibGunzip),
    deflateSync: function (value, options) { return zlibDeflateRaw(value, options); },
    inflateSync: function (value) { return zlibInflateRaw(value); },
    deflate: zlibAsync(zlibDeflateRaw),
    inflate: zlibAsync(zlibInflateRaw),
    constants: {
      Z_NO_COMPRESSION: 0,
      Z_BEST_SPEED: 1,
      Z_BEST_COMPRESSION: 9,
      Z_DEFAULT_COMPRESSION: -1
    }
  };

  // A small but functional Node stream layer. Node libraries bundled with Playwright (yazl/
  // yauzl for HAR zip, download streaming) rely on flowing-mode Readable, _write/_transform
  // hooks, pipe(), and finish/end/close ordering. Keep semantics close to Node, minimal scope.
  // The stream classes are function constructors (not ES6 classes) so bundled CommonJS libraries
  // can inherit them via util.inherits + `Readable.call(this)` / `Transform.call(this)` (e.g.
  // fd-slicer's zip entry read stream `xe` does `Readable.call(this)`). ES6 classes throw when
  // invoked without `new`; function constructors support `new`, `.call(this)`, and being extended.
  //
  // Node's per-stream state objects are de-facto API: bundled libraries read them directly. ws
  // decides how to finish a WebSocket from socket._readableState.endEmitted and the Receiver's
  // _writableState.finished / errorEmitted / needDrain, and reports socket._writableState.length
  // as bufferedAmount; fd-slicer sizes reads by _readableState.highWaterMark. So the states below
  // are updated where the transition happens ('end' emitted, end() called, 'finish' emitted,
  // destroyed, ...), never filled with fixed values.
  function streamNextTick(callback) {
    const args = Array.prototype.slice.call(arguments, 1);
    Promise.resolve().then(function () {
      callback.apply(null, args);
    });
  }

  function ReadableState(options, stream) {
    options = options || {};
    this.objectMode = !!(options.objectMode || options.readableObjectMode);
    this.highWaterMark = typeof options.highWaterMark === 'number'
      ? options.highWaterMark
      : (this.objectMode ? 16 : 16384);
    // null until a consumer appears, true while flowing ('data' events), false once paused.
    this.flowing = null;
    // push(null) was called / 'end' was emitted (only after the buffer has been consumed).
    this.ended = false;
    this.endEmitted = false;
    this.reading = false;
    this.resumeScheduled = false;
    this.destroyed = false;
    this.closed = false;
    this.closeEmitted = false;
    this.errored = null;
    this.errorEmitted = false;
    Object.defineProperty(this, 'length', {
      enumerable: true,
      get: function () {
        return stream.readableLength;
      }
    });
  }

  function WritableState(options) {
    options = options || {};
    this.objectMode = !!(options.objectMode || options.writableObjectMode);
    this.highWaterMark = typeof options.highWaterMark === 'number'
      ? options.highWaterMark
      : (this.objectMode ? 16 : 16384);
    // Bytes (objects in objectMode) passed to write() whose _write callback has not run yet.
    this.length = 0;
    // One _write at a time, like Node: chunks written meanwhile (or while corked) wait here.
    this.writing = false;
    this.buffered = [];
    this.bufferProcessing = false;
    this.pendingcb = 0;
    this.corked = 0;
    // write() returned false; 'drain' is emitted once length is back to 0.
    this.needDrain = false;
    // end() was called / _final has been started / 'finish' was emitted.
    this.ending = false;
    this.ended = false;
    this.finalCalled = false;
    this.finished = false;
    this.destroyed = false;
    this.closed = false;
    this.closeEmitted = false;
    this.errored = null;
    this.errorEmitted = false;
    this.onFinished = [];
  }

  function streamStates(stream) {
    const states = [];
    if (stream._readableState) {
      states.push(stream._readableState);
    }
    if (stream._writableState) {
      states.push(stream._writableState);
    }
    return states;
  }

  function isStreamDestroyed(stream) {
    return streamStates(stream).some(function (state) { return state.destroyed; });
  }

  function emitStreamClose(stream) {
    if (typeof stream.fireSticky === 'function') {
      stream.fireSticky('close');
    } else {
      stream.emit('close');
    }
  }

  // Node's destroy(): idempotent; marks both sides destroyed at once, runs _destroy(err, cb), then
  // emits the error _destroy called back with (at most once per stream; a _destroy that calls
  // back without one swallows it) followed by 'close' (once, even if an 'error' without listener
  // throws). Node emits both on a later tick; the base streams here keep their synchronous timing
  // unless emitAsync is set (sockets).
  function destroyStream(stream, error, emitAsync) {
    if (isStreamDestroyed(stream)) {
      return stream;
    }
    const states = streamStates(stream);
    states.forEach(function (state) {
      state.destroyed = true;
      if (error && !state.errored) {
        state.errored = error;
      }
    });
    if (stream._readableState) {
      stream.readable = false;
    }
    if (stream._writableState) {
      stream.writable = false;
    }
    let completed = false;
    const done = function (laterError) {
      if (completed) {
        return;
      }
      completed = true;
      const failure = laterError || null;
      const emit = function () {
        if (stream._writableState) {
          const wstate = stream._writableState;
          wstate.buffered.splice(0).forEach(function (request) {
            wstate.length -= request.length;
            wstate.pendingcb--;
            if (request.callback) {
              request.callback(streamDestroyedError('write'));
            }
          });
        }
        const finishCallbacks = stream._writableState && !stream._writableState.finished
          ? stream._writableState.onFinished.splice(0)
          : [];
        states.forEach(function (state) { state.closed = true; });
        try {
          if (failure && !states.some(function (state) { return state.errorEmitted; })) {
            states.forEach(function (state) {
              state.errorEmitted = true;
              if (!state.errored) {
                state.errored = failure;
              }
            });
            stream.emit('error', failure);
          }
        } finally {
          finishCallbacks.forEach(function (callback) {
            callback(failure || streamDestroyedError('end'));
          });
          if (!states.some(function (state) { return state.closeEmitted; })) {
            states.forEach(function (state) { state.closeEmitted = true; });
            emitStreamClose(stream);
          }
        }
      };
      if (emitAsync) {
        streamNextTick(emit);
      } else {
        emit();
      }
    };
    let destroyed = false;
    try {
      stream._destroy(error || null, function (laterError) {
        destroyed = true;
        done(laterError);
      });
    } catch (thrown) {
      if (destroyed) {
        // Thrown by an 'error'/'close' listener of a synchronous emit, not by _destroy.
        throw thrown;
      }
      done(thrown);
    }
    return stream;
  }

  function streamDestroyedError(method) {
    const error = new Error('Cannot call ' + method + ' after a stream was destroyed');
    error.code = 'ERR_STREAM_DESTROYED';
    return error;
  }

  // Node's errorOrDestroy with the default autoDestroy: a failed write destroys the stream with
  // the error ('error', then 'close').
  function errorOrDestroyStream(stream, error) {
    if (!isStreamDestroyed(stream)) {
      stream.destroy(error);
    }
  }

  function writableChunkLength(state, chunk) {
    if (state.objectMode) {
      return 1;
    }
    return chunk !== undefined && chunk !== null && typeof chunk.length === 'number' ? chunk.length : 0;
  }

  // Shared write() of Writable and Duplex: accounts the chunk in _writableState until _write
  // calls back, hands chunks to _write one at a time (buffering while a write is in flight or the
  // stream is corked), returns false at the high-water mark (needDrain) and emits 'drain' on a
  // later tick once everything has been written, like Node.
  function writeToStream(stream, chunk, encoding, callback) {
    if (typeof encoding === 'function') {
      callback = encoding;
      encoding = undefined;
    }
    const state = stream._writableState;
    if (state.destroyed || state.ending) {
      // Node also destroys the stream on a write after end(); dropping the chunk is enough here.
      if (callback) {
        streamNextTick(callback, state.destroyed ? streamDestroyedError('write') : writeAfterEndError());
      }
      return false;
    }
    const length = writableChunkLength(state, chunk);
    state.length += length;
    state.pendingcb++;
    state.buffered.push({ chunk: chunk, encoding: encoding, callback: callback, length: length });
    flushWriteBuffer(stream);
    // As in Node 20+, measured after the write: a chunk _write consumed synchronously does not
    // count, only bytes still pending. A _write that failed synchronously has destroyed the
    // stream already (ws pauses the socket on false).
    const ret = state.length < state.highWaterMark || state.length === 0;
    if (!ret) {
      state.needDrain = true;
    }
    return ret && !state.destroyed;
  }

  function writeAfterEndError() {
    const error = new Error('write after end');
    error.code = 'ERR_STREAM_WRITE_AFTER_END';
    return error;
  }

  function flushWriteBuffer(stream) {
    const state = stream._writableState;
    if (state.bufferProcessing) {
      return;
    }
    state.bufferProcessing = true;
    // A write callback that throws (see writeOne) must not strand the chunks behind it: keep
    // writing and rethrow the first such exception afterwards.
    let failed = false;
    let failure;
    try {
      while (!state.writing && !state.corked && !state.destroyed && state.buffered.length > 0) {
        try {
          writeOne(stream, state.buffered.shift());
        } catch (error) {
          if (!failed) {
            failed = true;
            failure = error;
          }
        }
      }
    } finally {
      state.bufferProcessing = false;
    }
    if (failed) {
      throw failure;
    }
  }

  // _write usually calls back synchronously here, so the write callback (and whatever it
  // triggers: 'drain', 'finish', 'close' listeners) runs inside the try below. Only a throw from
  // _write itself fails the write; one from after the callback ran propagates to the caller (and
  // from there to the event loop's error reporting) once the bookkeeping is done.
  function writeOne(stream, request) {
    const state = stream._writableState;
    state.writing = true;
    let called = false;
    const onwrite = function (error) {
      if (called) {
        return;
      }
      called = true;
      state.writing = false;
      state.length -= request.length;
      state.pendingcb--;
      if (error) {
        try {
          if (request.callback) {
            request.callback(error);
          }
        } finally {
          errorOrDestroyStream(stream, error);
        }
        return;
      }
      try {
        if (request.callback) {
          request.callback();
        }
      } finally {
        if (state.needDrain && state.length === 0) {
          streamNextTick(function () {
            if (state.needDrain && state.length === 0 && !state.destroyed) {
              state.needDrain = false;
              stream.emit('drain');
            }
          });
        }
        flushWriteBuffer(stream);
        finishStreamIfDone(stream);
      }
    };
    try {
      if (typeof stream._write === 'function') {
        stream._write(request.chunk, request.encoding, onwrite);
      } else {
        onwrite();
      }
    } catch (error) {
      if (called) {
        throw error;
      }
      onwrite(error);
    }
  }

  // Shared end() of Writable and Duplex. Idempotent: a second end() only waits for 'finish'.
  function endStream(stream, chunk, encoding, callback) {
    if (typeof chunk === 'function') {
      callback = chunk;
      chunk = undefined;
    } else if (typeof encoding === 'function') {
      callback = encoding;
      encoding = undefined;
    }
    const state = stream._writableState;
    if (chunk !== undefined && chunk !== null && !state.ending) {
      writeToStream(stream, chunk, encoding);
    }
    if (state.corked) {
      state.corked = 0;
      flushWriteBuffer(stream);
    }
    if (callback) {
      if (state.finished) {
        streamNextTick(callback);
      } else if (state.destroyed) {
        streamNextTick(callback, state.errored || streamDestroyedError('end'));
      } else {
        state.onFinished.push(callback);
      }
    }
    if (!state.ending) {
      state.ending = true;
      state.ended = true;
      stream.writable = false;
      finishStreamIfDone(stream);
    }
    return stream;
  }

  // Once end() was called and every write has called back: run _final, then mark the writable
  // side finished, call the end() callbacks and emit 'finish' (Node's order), then auto-destroy.
  // As in writeOne, only a throw from _final itself fails the stream; a throwing callback or
  // listener propagates after the stream has still been finished and destroyed.
  function finishStreamIfDone(stream) {
    const state = stream._writableState;
    if (!state.ending || state.finalCalled || state.finished || state.destroyed || state.pendingcb > 0) {
      return;
    }
    state.finalCalled = true;
    let completed = false;
    const done = function (error) {
      if (completed) {
        return;
      }
      completed = true;
      if (error) {
        const callbacks = state.onFinished.splice(0);
        try {
          callbacks.forEach(function (callback) { callback(error); });
        } finally {
          errorOrDestroyStream(stream, error);
        }
        return;
      }
      if (state.destroyed) {
        return;
      }
      state.finished = true;
      const callbacks = state.onFinished.splice(0);
      try {
        callbacks.forEach(function (callback) { callback(); });
        stream._emitFinish();
      } finally {
        stream._afterFinish();
      }
    };
    if (typeof stream._final === 'function') {
      try {
        stream._final(done);
      } catch (error) {
        if (completed) {
          throw error;
        }
        done(error);
      }
    } else {
      done();
    }
  }

  function Readable(options) {
    EventEmitter.call(this);
    this.readable = true;
    this._readableBuffer = [];
    this._reading = false;
    this._readableState = new ReadableState(options, this);
    if (options && typeof options.read === 'function') {
      this._read = options.read;
    }
    if (options && typeof options.destroy === 'function') {
      this._destroy = options.destroy;
    }
  }
  Readable.prototype = Object.create(EventEmitter.prototype);
  Readable.prototype.constructor = Readable;

  // Pull-based source streams (e.g. fd-slicer's zip entry reader) implement _read() and only
  // produce data when it is invoked. Drive _read while flowing so such streams actually emit
  // their bytes and reach end; without this the zip read pipeline never completes.
  Readable.prototype._maybeRead = function () {
    const state = this._readableState;
    if (state.ended || state.destroyed || this._reading || state.flowing !== true || typeof this._read !== 'function') {
      return;
    }
    this._reading = true;
    state.reading = true;
    try {
      this._read(65536);
    } catch (error) {
      this.emit('error', error);
    }
    this._reading = false;
    state.reading = false;
  };

  // Emits 'end' at most once; endEmitted turns true right before, as in Node.
  Readable.prototype._emitEnd = function () {
    const state = this._readableState;
    if (state.endEmitted || state.errored || state.closeEmitted) {
      return;
    }
    state.endEmitted = true;
    this.readable = false;
    this.emit('end');
    this._afterEnd();
  };

  // Node emits 'end' on a later tick once an ended stream has been read empty.
  Readable.prototype._scheduleEnd = function () {
    const self = this;
    streamNextTick(function () {
      if (self._readableBuffer.length === 0) {
        self._emitEnd();
      }
    });
  };

  // Hook for Duplex: what happens once the readable side has ended.
  Readable.prototype._afterEnd = function () {};

  Readable.prototype._destroy = function (error, callback) {
    callback(error);
  };

  Readable.prototype.push = function (chunk) {
    const state = this._readableState;
    if (chunk === null) {
      if (state.ended) {
        return false;
      }
      state.ended = true;
      // Keep `readable` true until the buffer is drained so pull-mode consumers can finish
      // reading any buffered bytes; flowing-mode consumers get 'end' immediately.
      if (state.flowing === true && this._readableBuffer.length === 0) {
        this._emitEnd();
      } else {
        this.emit('readable');
      }
      return false;
    }
    // Like Node, data is dropped once the stream is destroyed (or ended), but the EOF above is
    // still taken: fd-slicer (yauzl) sets `destroyed = true` itself right before push(null).
    if (state.destroyed || state.ended) {
      return false;
    }
    this._readableBuffer.push(chunk);
    if (state.flowing === true) {
      this._flowBuffered();
      // Ask the source for more (Node calls _read again after each consumed chunk).
      this._maybeRead();
    } else {
      // Pull mode: notify consumers that data is available to read().
      this.emit('readable');
    }
    return true;
  };

  // Puts data back at the front of the buffer (ws: bytes that arrived together with the HTTP
  // upgrade request). It is delivered before anything pushed later.
  Readable.prototype.unshift = function (chunk) {
    const state = this._readableState;
    if (state.destroyed || chunk === null || chunk === undefined) {
      return;
    }
    this._readableBuffer.unshift(chunk);
    if (state.flowing === true) {
      this._flowBuffered();
    } else {
      this.emit('readable');
    }
  };

  // Emits buffered chunks while flowing (a listener may pause the stream in between), then
  // 'end' once the stream has ended and nothing is left.
  Readable.prototype._flowBuffered = function () {
    const state = this._readableState;
    // Node's read() emits 'data' only until 'error'/'close' was emitted; read() still returns
    // whatever is buffered (ws drains a destroyed socket that way).
    while (state.flowing === true && this._readableBuffer.length > 0 && !state.closeEmitted && !state.errorEmitted) {
      this.emit('data', this._readableBuffer.shift());
    }
    if (state.flowing === true && state.ended && this._readableBuffer.length === 0) {
      this._emitEnd();
    }
  };

  // Pull-based read used by Playwright's StreamDispatcher (download.createReadStream): returns
  // up to `size` buffered bytes, or null when nothing is currently buffered.
  Readable.prototype.read = function (size) {
    const state = this._readableState;
    if (this._readableBuffer.length === 0) {
      if (state.ended) {
        this.readable = false;
        this._scheduleEnd();
      }
      return null;
    }
    let pending = this._readableBuffer.length === 1
      ? this._readableBuffer[0]
      : global.Buffer.concat(this._readableBuffer);
    this._readableBuffer = [];
    // A non-positive/NaN/absent size means "return all currently available" (Node semantics).
    // Playwright's StreamDispatcher calls read() with no usable size, which arrives as NaN.
    if (!(size > 0) || pending.length <= size) {
      if (state.ended) {
        this.readable = false;
        this._scheduleEnd();
      }
      return pending;
    }
    // Zero-copy split: subarray returns a Buffer view sharing the backing store, so chunked reads
    // of a large buffered artifact (e.g. a multi-MB trace) stay O(n) overall instead of re-copying
    // the remainder on every read.
    const head = pending.subarray(0, size);
    this._readableBuffer = [pending.subarray(size)];
    return head;
  };

  Object.defineProperty(Readable.prototype, 'readableEnded', {
    get: function () {
      return this._readableState.ended && this._readableBuffer.length === 0;
    }
  });

  Object.defineProperty(Readable.prototype, 'readableFlowing', {
    get: function () {
      return this._readableState.flowing;
    },
    // Node's http server sets socket.readableFlowing = null before handing a socket to its
    // 'upgrade' listeners: incoming bytes are buffered until the new owner attaches 'data'.
    set: function (value) {
      this._readableState.flowing = value;
    }
  });

  Object.defineProperty(Readable.prototype, 'destroyed', {
    get: function () {
      return isStreamDestroyed(this);
    },
    set: function (value) {
      streamStates(this).forEach(function (state) { state.destroyed = !!value; });
    }
  });

  // Total bytes currently buffered. Playwright's StreamDispatcher sizes its reads with
  // Math.min(stream.readableLength, params.size); without this it computes NaN and pulls the
  // whole buffer at once, overflowing the client's fixed-size read buffer for large artifacts.
  Object.defineProperty(Readable.prototype, 'readableLength', {
    get: function () {
      let total = 0;
      for (let index = 0; index < this._readableBuffer.length; index++) {
        const chunk = this._readableBuffer[index];
        total += chunk && chunk.length ? chunk.length : 0;
      }
      return total;
    }
  });

  Readable.from = function (iterable) {
    const stream = new Readable();
    Promise.resolve().then(function () {
      try {
        if (iterable === undefined || iterable === null) {
          stream.push(null);
        } else if (typeof iterable === 'string' || global.Buffer.isBuffer(iterable) || iterable instanceof Uint8Array) {
          stream.push(iterable);
          stream.push(null);
        } else if (typeof iterable[Symbol.iterator] === 'function') {
          const list = Array.from(iterable);
          for (let index = 0; index < list.length; index++) {
            stream.push(list[index]);
          }
          stream.push(null);
        } else if (typeof iterable[Symbol.asyncIterator] === 'function') {
          (async function () {
            for await (const item of iterable) {
              stream.push(item);
            }
            stream.push(null);
          })().catch(function (error) { stream.emit('error', error); });
        } else {
          stream.push(iterable);
          stream.push(null);
        }
      } catch (error) {
        stream.emit('error', error);
      }
    });
    return stream;
  };

  Readable.prototype.on = function (name, listener) {
    const result = EventEmitter.prototype.on.call(this, name, listener);
    if (name === 'data') {
      // Node: a 'data' listener starts the flow unless the stream was explicitly paused.
      if (this._readableState.flowing !== false) {
        this.resume();
      }
    } else if (name === 'readable' && (this._readableBuffer.length > 0 || this._readableState.ended)) {
      // Node re-signals readability to listeners attached after data is already buffered (or
      // after end). Playwright's StreamDispatcher attaches 'readable'/'end' per read() call,
      // often after createReadStream already pushed everything, so replay the signal async.
      const self = this;
      Promise.resolve().then(function () { self.emit('readable'); });
    } else if (name === 'end' && this._readableState.endEmitted) {
      // ... and the 'end' itself (StreamDispatcher may be created after the stream ended).
      const self = this;
      Promise.resolve().then(function () { listener.call(self); });
    } else if (name === 'end' && this._readableState.ended && this._readableBuffer.length === 0) {
      this._scheduleEnd();
    }
    return result;
  };
  Readable.prototype.addListener = Readable.prototype.on;

  // Like Node, resume() (also implied by attaching a 'data' listener) switches to flowing mode at
  // once but delivers buffered data on a later tick. Callers rely on that: ws attaches its 'data'
  // listener in setSocket() before it is set up to receive messages (readyState, 'end'/'error'
  // listeners, the 'connection' callback), so buffered bytes must not be emitted synchronously.
  Readable.prototype.resume = function () {
    const state = this._readableState;
    if (state.flowing === true) {
      return this;
    }
    state.flowing = true;
    if (!state.resumeScheduled) {
      state.resumeScheduled = true;
      const self = this;
      streamNextTick(function () {
        state.resumeScheduled = false;
        if (state.flowing !== true) {
          return;
        }
        self._flowBuffered();
        if (!state.ended) {
          // Kick off pull-based sources (_read) now that we are flowing.
          self._maybeRead();
        }
      });
    }
    return this;
  };

  Readable.prototype.pause = function () {
    this._readableState.flowing = false;
    return this;
  };

  Readable.prototype.isPaused = function () {
    return this._readableState.flowing === false;
  };

  Readable.prototype.setEncoding = function () {
    return this;
  };

  // pipe(destination, { end }) and unpipe(). { end: false } matters: yazl pipes every zip entry
  // into its output stream with it and ends that stream itself after the central directory, so
  // ending it with the first entry truncates the archive. Errors are not forwarded (Node doesn't
  // either): with two sockets piped into each other (client-certificate proxy tunnel) forwarding
  // recursed until the stack overflowed.
  Readable.prototype.pipe = function (destination, options) {
    const self = this;
    const endDestination = !options || options.end !== false;
    const pipe = {
      destination: destination,
      ondata: function (chunk) {
        if (destination && typeof destination.write === 'function') {
          destination.write(chunk);
        }
      },
      onend: function () {
        if (endDestination && destination && typeof destination.end === 'function') {
          destination.end();
        }
      }
    };
    (this._pipes || (this._pipes = [])).push(pipe);
    this.on('data', pipe.ondata);
    this.on('end', pipe.onend);
    if (destination && typeof destination.emit === 'function') {
      destination.emit('pipe', self);
    }
    return destination;
  };

  Readable.prototype.unpipe = function (destination) {
    const self = this;
    const pipes = this._pipes || [];
    this._pipes = pipes.filter(function (pipe) {
      if (destination !== undefined && pipe.destination !== destination) {
        return true;
      }
      self.removeListener('data', pipe.ondata);
      self.removeListener('end', pipe.onend);
      if (pipe.destination && typeof pipe.destination.emit === 'function') {
        pipe.destination.emit('unpipe', self);
      }
      return false;
    });
    if (pipes.length > 0 && this._pipes.length === 0) {
      this.pause();
    }
    return this;
  };

  Readable.prototype.destroy = function (error) {
    return destroyStream(this, error, false);
  };

  function Writable(options) {
    EventEmitter.call(this);
    this.writable = true;
    this._writableState = new WritableState(options);
    // One-shot lifecycle events (open/ready/finish/close) are "sticky": consumers frequently
    // attach a listener (e.g. await once(stream, 'close')) right after calling end(), i.e. after
    // we already emitted. Record fired events and replay them to late listeners so awaits resolve.
    this._firedEvents = {};
    if (options && typeof options.write === 'function') {
      this._write = options.write;
    }
    if (options && typeof options.final === 'function') {
      this._final = options.final;
    }
    if (options && typeof options.destroy === 'function') {
      this._destroy = options.destroy;
    }
  }
  Writable.prototype = Object.create(EventEmitter.prototype);
  Writable.prototype.constructor = Writable;

  Writable.prototype.fireSticky = function (name) {
    const args = Array.prototype.slice.call(arguments, 1);
    this._firedEvents[name] = args;
    this.emit.apply(this, arguments);
  };

  Writable.prototype.on = function (name, listener) {
    const result = EventEmitter.prototype.on.call(this, name, listener);
    if (this._firedEvents && Object.prototype.hasOwnProperty.call(this._firedEvents, name)) {
      const args = this._firedEvents[name];
      const self = this;
      Promise.resolve().then(function () { listener.apply(self, args); });
    }
    return result;
  };
  Writable.prototype.addListener = Writable.prototype.on;

  Writable.prototype.write = function (chunk, encoding, callback) {
    return writeToStream(this, chunk, encoding, callback);
  };

  Writable.prototype.end = function (chunk, encoding, callback) {
    return endStream(this, chunk, encoding, callback);
  };

  Writable.prototype._emitFinish = function () {
    this.fireSticky('finish');
  };

  // Node's autoDestroy: a finished Writable is destroyed, which emits 'close'.
  Writable.prototype._afterFinish = function () {
    destroyStream(this, null, false);
  };

  Writable.prototype._destroy = function (error, callback) {
    callback(error);
  };

  Writable.prototype.destroy = function (error) {
    return destroyStream(this, error, false);
  };

  Writable.prototype.cork = function () {
    this._writableState.corked++;
  };

  Writable.prototype.uncork = function () {
    const state = this._writableState;
    if (state.corked > 0) {
      state.corked--;
      if (state.corked === 0) {
        flushWriteBuffer(this);
        finishStreamIfDone(this);
      }
    }
  };

  Object.defineProperty(Writable.prototype, 'destroyed', {
    get: function () {
      return isStreamDestroyed(this);
    },
    set: function (value) {
      streamStates(this).forEach(function (state) { state.destroyed = !!value; });
    }
  });

  Object.defineProperty(Writable.prototype, 'writableEnded', {
    get: function () {
      return this._writableState.ending;
    }
  });

  Object.defineProperty(Writable.prototype, 'writableFinished', {
    get: function () {
      return this._writableState.finished;
    }
  });

  Object.defineProperty(Writable.prototype, 'writableLength', {
    get: function () {
      return this._writableState.length;
    }
  });

  Object.defineProperty(Writable.prototype, 'writableNeedDrain', {
    get: function () {
      return this._writableState.needDrain;
    }
  });

  // Transform is both readable and writable: written chunks pass through _transform and are
  // pushed to the readable side.
  function Transform(options) {
    Readable.call(this, options);
    this.writable = true;
    this._writableState = new WritableState(options);
    if (options && typeof options.transform === 'function') {
      this._transform = options.transform;
    }
    if (options && typeof options.flush === 'function') {
      this._flush = options.flush;
    }
  }
  Transform.prototype = Object.create(Readable.prototype);
  Transform.prototype.constructor = Transform;

  Transform.prototype.write = function (chunk, encoding, callback) {
    if (typeof encoding === 'function') {
      callback = encoding;
      encoding = undefined;
    }
    const self = this;
    try {
      if (typeof this._transform === 'function') {
        this._transform(chunk, encoding, function (error, data) {
          if (data !== undefined && data !== null) {
            self.push(data);
          }
          if (error) {
            self._writableState.errorEmitted = true;
            self.emit('error', error);
          }
          if (callback) {
            callback(error);
          }
        });
      } else {
        this.push(chunk);
        if (callback) {
          callback();
        }
      }
    } catch (error) {
      this._writableState.errorEmitted = true;
      this.emit('error', error);
    }
    return true;
  };

  Transform.prototype.end = function (chunk, encoding, callback) {
    if (typeof chunk === 'function') {
      callback = chunk;
      chunk = undefined;
    } else if (typeof encoding === 'function') {
      callback = encoding;
      encoding = undefined;
    }
    const self = this;
    const state = this._writableState;
    if (state.ending) {
      if (callback) {
        if (state.finished) {
          streamNextTick(callback);
        } else {
          state.onFinished.push(callback);
        }
      }
      return this;
    }
    state.ending = true;
    state.ended = true;
    this.writable = false;
    const finish = function (error) {
      self.push(null);
      state.finished = true;
      self.emit('finish');
      state.onFinished.splice(0).forEach(function (finished) { finished(); });
      if (callback) {
        callback(error);
      }
    };
    const doEnd = function () {
      if (typeof self._flush === 'function') {
        self._flush(function (error, data) {
          if (data !== undefined && data !== null) {
            self.push(data);
          }
          finish(error);
        });
      } else {
        finish();
      }
    };
    if (chunk !== undefined && chunk !== null) {
      this.write(chunk, encoding, doEnd);
    } else {
      doEnd();
    }
    return this;
  };

  function PassThrough(options) {
    Transform.call(this, options);
    this._transform = function (chunk, encoding, callback) {
      callback(null, chunk);
    };
  }
  PassThrough.prototype = Object.create(Transform.prototype);
  PassThrough.prototype.constructor = PassThrough;

  // A real Duplex: independent readable (via push, inherited from Readable) and writable sides,
  // honouring the { read, write, final, destroy } option callbacks. Distinct from Transform (whose
  // write feeds its own readable side). Playwright's client-certificate interceptor builds
  // `new stream.Duplex({ read, write, destroy })` whose write must forward bytes to the SOCKS proxy
  // rather than loop back into the readable side. Like Node: allowHalfOpen (default true) keeps
  // the writable side open after the readable side ended, and the stream destroys itself
  // ('close') once both sides are done.
  function Duplex(options) {
    Readable.call(this, options);
    this.writable = true;
    this._writableState = new WritableState(options);
    this.allowHalfOpen = !(options && options.allowHalfOpen === false);
    if (options && typeof options.write === 'function') {
      this._write = options.write;
    }
    if (options && typeof options.final === 'function') {
      this._final = options.final;
    }
  }
  Duplex.prototype = Object.create(Readable.prototype);
  Duplex.prototype.constructor = Duplex;

  Duplex.prototype.write = function (chunk, encoding, callback) {
    return writeToStream(this, chunk, encoding, callback);
  };

  Duplex.prototype.end = function (chunk, encoding, callback) {
    return endStream(this, chunk, encoding, callback);
  };

  Duplex.prototype.cork = Writable.prototype.cork;
  Duplex.prototype.uncork = Writable.prototype.uncork;

  Duplex.prototype._emitFinish = function () {
    this.emit('finish');
  };

  Duplex.prototype._afterFinish = function () {
    if (this._readableState.endEmitted) {
      this.destroy();
    }
  };

  Duplex.prototype._afterEnd = function () {
    if (!this.allowHalfOpen && !this._writableState.ending && !this._writableState.destroyed) {
      this.end();
    }
    if (this._writableState.finished) {
      this.destroy();
    }
  };

  Duplex.prototype.destroy = function (error) {
    return destroyStream(this, error, false);
  };

  ['writableEnded', 'writableFinished', 'writableLength', 'writableNeedDrain'].forEach(function (name) {
    Object.defineProperty(Duplex.prototype, name, Object.getOwnPropertyDescriptor(Writable.prototype, name));
  });

  function streamFinished(stream, optionsOrCallback, maybeCallback) {
    const callback = typeof optionsOrCallback === 'function' ? optionsOrCallback : maybeCallback;
    let done = false;
    const finish = function (error) {
      if (done) {
        return;
      }
      done = true;
      if (callback) {
        callback(error || null);
      }
    };
    stream.on('end', function () { finish(); });
    stream.on('finish', function () { finish(); });
    stream.on('close', function () { finish(); });
    stream.on('error', function (error) { finish(error); });
    return function () { done = true; };
  }

  function streamPipeline() {
    const args = Array.prototype.slice.call(arguments);
    const callback = typeof args[args.length - 1] === 'function' ? args.pop() : null;
    let done = false;
    const finish = function (error) {
      if (done) {
        return;
      }
      done = true;
      if (callback) {
        callback(error || null);
      }
    };
    for (let index = 0; index < args.length; index++) {
      if (args[index] && typeof args[index].on === 'function') {
        args[index].on('error', finish);
      }
    }
    for (let index = 0; index < args.length - 1; index++) {
      args[index].pipe(args[index + 1]);
    }
    const last = args[args.length - 1];
    streamFinished(last, finish);
    return last;
  }

  modules.stream = {
    Stream: Readable,
    Readable: Readable,
    Writable: Writable,
    Duplex: Duplex,
    Transform: Transform,
    PassThrough: PassThrough,
    finished: streamFinished,
    pipeline: streamPipeline,
    promises: {
      finished: function (stream, options) {
        return new Promise(function (resolve, reject) {
          streamFinished(stream, function (error) {
            if (error) { reject(error); } else { resolve(); }
          });
        });
      },
      pipeline: function () {
        const args = Array.prototype.slice.call(arguments);
        return new Promise(function (resolve, reject) {
          args.push(function (error) {
            if (error) { reject(error); } else { resolve(); }
          });
          streamPipeline.apply(null, args);
        });
      }
    }
  };

  // File streams, backed by the host filesystem. createWriteStream buffers and flushes on end;
  // createReadStream emits the file's bytes once and ends. These let Playwright's HAR/zip and
  // download code complete their pipe()/end() lifecycles (finish/close) so the commands settle
  // instead of awaiting forever (which previously wedged BrowserContext.close()).
  modules.fs.createWriteStream = function (path, options) {
    const target = String(path);
    const chunks = [];
    const stream = new Writable();
    stream.path = target;
    stream.bytesWritten = 0;
    stream._write = function (chunk, encoding, callback) {
      const buffer = global.Buffer.isBuffer(chunk)
        ? chunk
        : global.Buffer.from(String(chunk), typeof encoding === 'string' ? encoding : 'utf8');
      chunks.push(buffer);
      stream.bytesWritten += buffer.length;
      callback();
    };
    stream._final = function (callback) {
      try {
        const all = chunks.length > 0 ? global.Buffer.concat(chunks) : global.Buffer.alloc(0);
        host.fileSystem().writeFileBase64(target, all.toString('base64'));
        callback();
      } catch (error) {
        stream.emit('error', error);
        callback();
      }
    };
    Promise.resolve().then(function () {
      stream.fireSticky('open', 0);
      stream.fireSticky('ready');
    });
    return stream;
  };

  modules.fs.createReadStream = function (path, options) {
    const target = String(path);
    const encoding = readEncoding(options);
    const stream = new Readable();
    stream.path = target;
    Promise.resolve().then(function () {
      try {
        const buffer = hostReadFile(target);
        stream.emit('open', 0);
        stream.push(encoding ? buffer.toString(encoding) : buffer);
        stream.push(null);
      } catch (error) {
        stream.emit('error', error);
      }
    });
    return stream;
  };

  // Playwright 1.62's get-stream bundle imports node:stream/promises directly.
  modules['stream/promises'] = modules.stream.promises;

  // Streaming zlib transforms (buffer all input, (de)compress on flush). Playwright's zip code
  // may pipe through createDeflateRaw/createInflateRaw rather than the one-shot helpers.
  function makeZlibTransform(compute, options) {
    const chunks = [];
    const transform = new Transform();
    transform._transform = function (chunk, encoding, callback) {
      chunks.push(toInputBuffer(chunk));
      callback();
    };
    transform._flush = function (callback) {
      try {
        const all = chunks.length > 0 ? global.Buffer.concat(chunks) : global.Buffer.alloc(0);
        callback(null, compute(all, options));
      } catch (error) {
        callback(error);
      }
    };
    return transform;
  }

  modules.zlib.createDeflateRaw = function (options) { return makeZlibTransform(zlibDeflateRaw, options); };
  modules.zlib.createInflateRaw = function (options) { return makeZlibTransform(zlibInflateRaw, options); };
  modules.zlib.createGzip = function (options) { return makeZlibTransform(zlibGzip, options); };
  modules.zlib.createGunzip = function (options) { return makeZlibTransform(zlibGunzip, options); };

  // Constructor forms (new zlib.DeflateRaw()) used by yazl's streaming compression. A constructor
  // that returns an object yields that object from `new`, so these produce the transform stream.
  modules.zlib.DeflateRaw = function (options) { return makeZlibTransform(zlibDeflateRaw, options); };
  modules.zlib.InflateRaw = function (options) { return makeZlibTransform(zlibInflateRaw, options); };
  modules.zlib.Gzip = function (options) { return makeZlibTransform(zlibGzip, options); };
  modules.zlib.Gunzip = function (options) { return makeZlibTransform(zlibGunzip, options); };

  modules.readline = {
    createInterface: function (options) {
      const reader = new EventEmitter();
      const input = options && options.input;
      let pending = '';
      if (input && typeof input.on === 'function') {
        input.on('data', function (chunk) {
          pending += String(chunk);
          let newlineIndex = pending.indexOf('\n');
          while (newlineIndex >= 0) {
            const line = pending.substring(0, newlineIndex).replace(/\r$/, '');
            pending = pending.substring(newlineIndex + 1);
            reader.emit('line', line);
            newlineIndex = pending.indexOf('\n');
          }
        });
        input.on('end', function () {
          if (pending.length > 0) {
            reader.emit('line', pending.replace(/\r$/, ''));
            pending = '';
          }
          reader.emit('close');
        });
      }
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
      if (lowerName === 'set-cookie') {
        // Node keeps set-cookie as an array of individual cookies; Playwright iterates it.
        if (headers[lowerName] === undefined) {
          headers[lowerName] = [];
        }
        headers[lowerName].push(value);
      } else if (headers[lowerName] === undefined) {
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

  // Mirrors a Node IncomingMessage: a paused readable that only flushes its (already fully
  // buffered) body once a consumer subscribes ('data'/'end'/'readable' or resume()). This
  // matters because the response handler may await (e.g. addCookies) before registering its
  // body listeners; flushing eagerly would emit 'end' to nobody and the fetch would hang.
  function attachLazyBody(incomingMessage, body, request) {
    let flushed = false;
    function flush() {
      if (flushed) {
        return;
      }
      flushed = true;
      Promise.resolve().then(function () {
        if (body.length > 0) {
          incomingMessage.emit('data', body);
        }
        incomingMessage.complete = true;
        incomingMessage.emit('end');
        incomingMessage.emit('close');
        request.emit('close');
      });
    }

    const baseOn = incomingMessage.on.bind(incomingMessage);
    const baseOnce = incomingMessage.once.bind(incomingMessage);
    function maybeFlush(event) {
      if (event === 'data' || event === 'end' || event === 'readable') {
        flush();
      }
    }
    incomingMessage.on = function (event, listener) {
      const result = baseOn(event, listener);
      maybeFlush(event);
      return result;
    };
    incomingMessage.addListener = incomingMessage.on;
    incomingMessage.once = function (event, listener) {
      const result = baseOnce(event, listener);
      maybeFlush(event);
      return result;
    };
    incomingMessage.resume = function () {
      flush();
      return incomingMessage;
    };
    incomingMessage.read = function () {
      flush();
      return null;
    };
  }

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
    const body = global.Buffer.from(String(response.bodyBase64() || ''), 'base64');
    attachLazyBody(incomingMessage, body, request);

    if (entry.callback) {
      entry.callback(incomingMessage);
    }
    request.emit('response', incomingMessage);
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

  // Encodes the Node https TLS options (client certificate + trust policy) for the host so the
  // Java HTTP client can build a matching SSLContext. Used by APIRequestContext client certs.
  // Node accepts cert/key/pfx as a value, an array, or an array of wrapper objects:
  // key:[{pem,passphrase}], pfx:[{buf,passphrase}]; cert:[Buffer,...] (a chain).
  function tlsEntryToBuffer(entry) {
    if (entry === undefined || entry === null) {
      return null;
    }
    if (global.Buffer.isBuffer(entry)) {
      return entry;
    }
    if (entry instanceof Uint8Array) {
      return global.Buffer.from(entry);
    }
    if (typeof entry === 'object') {
      if (entry.pem !== undefined && entry.pem !== null) {
        return tlsEntryToBuffer(entry.pem);
      }
      if (entry.buf !== undefined && entry.buf !== null) {
        return tlsEntryToBuffer(entry.buf);
      }
      return null;
    }
    return global.Buffer.from(String(entry), 'utf8');
  }

  function tlsEntries(value) {
    if (value === undefined || value === null) {
      return [];
    }
    return Array.isArray(value) ? value : [value];
  }

  function tlsSingleBase64(value) {
    const entries = tlsEntries(value);
    if (entries.length === 0) {
      return null;
    }
    const buffer = tlsEntryToBuffer(entries[0]);
    return buffer && buffer.length > 0 ? buffer.toString('base64') : null;
  }

  function tlsChainBase64(value) {
    const parts = [];
    tlsEntries(value).forEach(function (entry) {
      const buffer = tlsEntryToBuffer(entry);
      if (buffer && buffer.length > 0) {
        parts.push(buffer);
        parts.push(global.Buffer.from('\n', 'utf8'));
      }
    });
    return parts.length > 0 ? global.Buffer.concat(parts).toString('base64') : null;
  }

  function tlsPassphrase(options) {
    if (options.passphrase !== undefined && options.passphrase !== null) {
      return String(options.passphrase);
    }
    const candidates = tlsEntries(options.pfx).concat(tlsEntries(options.key));
    for (let index = 0; index < candidates.length; index++) {
      const entry = candidates[index];
      if (entry && typeof entry === 'object' && entry.passphrase !== undefined && entry.passphrase !== null) {
        return String(entry.passphrase);
      }
    }
    return null;
  }

  function extractTlsOptions(options) {
    const pfx = tlsSingleBase64(options.pfx);
    const cert = tlsChainBase64(options.cert);
    const key = tlsSingleBase64(options.key);
    const rejectUnauthorized = options.rejectUnauthorized !== false;
    if (!pfx && !cert && !key && rejectUnauthorized) {
      return '';
    }
    const tls = { rejectUnauthorized: rejectUnauthorized };
    if (pfx) {
      tls.pfx = pfx;
    }
    if (cert) {
      tls.cert = cert;
    }
    if (key) {
      tls.key = key;
    }
    const passphrase = tlsPassphrase(options);
    if (passphrase !== null) {
      tls.passphrase = passphrase;
    }
    const ca = tlsChainBase64(options.ca);
    if (ca) {
      tls.ca = ca;
    }
    return JSON.stringify(tls);
  }

  // Playwright routes APIRequestContext traffic through a proxy by passing a proxy agent:
  // HttpsProxyAgent keeps the proxy as a URL in `proxy`, SocksProxyAgent its URL in `proxyUrl`.
  // The host connects through that proxy itself; Playwright already applied `bypass`.
  function agentProxyUrl(agent) {
    if (!agent) {
      return '';
    }
    if (typeof agent.proxyUrl === 'string') {
      return agent.proxyUrl;
    }
    if (agent.proxy && typeof agent.proxy.href === 'string') {
      return agent.proxy.href;
    }
    return '';
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
        requestId = host.httpClient().startRequest(method, url, flattenRequestHeaders(options), bodyBase64, extractTlsOptions(options), agentProxyUrl(options.agent));
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
  function dnsLookupAll(hostname, family) {
    const raw = String(host.netServer().resolve(String(hostname)) || '');
    const result = [];
    if (raw) {
      raw.split(',').forEach(function (entry) {
        const pair = entry.split('|');
        const entryFamily = parseInt(pair[1], 10);
        if (!family || family === entryFamily) {
          result.push({ address: pair[0], family: entryFamily });
        }
      });
    }
    return result;
  }
  modules.dns = {
    lookup: function (hostname, options, callback) {
      if (typeof options === 'function') {
        callback = options;
        options = {};
      }
      options = options || {};
      try {
        const all = dnsLookupAll(hostname, options.family);
        if (options.all) {
          callback(null, all);
        } else if (all.length > 0) {
          callback(null, all[0].address, all[0].family);
        } else {
          const error = new Error('getaddrinfo ENOTFOUND ' + hostname);
          error.code = 'ENOTFOUND';
          callback(error);
        }
      } catch (error) {
        callback(error);
      }
    },
    resolve: unsupported('dns.resolve'),
    promises: {
      lookup: function (hostname, options) {
        return new Promise(function (resolve, reject) {
          modules.dns.lookup(hostname, options || {}, function (error, address, family) {
            if (error) {
              reject(error);
            } else if (options && options.all) {
              resolve(address);
            } else {
              resolve({ address: address, family: family });
            }
          });
        });
      }
    }
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
    // Removes "." and ".." segments from a path, per RFC 3986. A trailing "." or ".." keeps
    // the directory trailing slash (e.g. ".../dir/." -> ".../dir/").
    const normalizeUrlPath = function (path) {
      const isAbsolute = path.charAt(0) === '/';
      const segments = path.split('/');
      const output = [];
      for (let index = 0; index < segments.length; index++) {
        const segment = segments[index];
        const isLast = index === segments.length - 1;
        if (segment === '.') {
          if (isLast) {
            output.push('');
          }
          continue;
        }
        if (segment === '..') {
          if (output.length > 0 && output[output.length - 1] !== '..') {
            output.pop();
          } else if (!isAbsolute) {
            output.push('..');
          }
          if (isLast) {
            output.push('');
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

    // WHATWG userinfo percent-encode set; '%' is not in it, so encoding is idempotent.
    function encodeUserinfo(value) {
      return String(value).replace(/[\u0000-\u0020"#<>?`{}\/:;=@\[\\\]^|\u007f-\u{10ffff}]/gu, encodeURIComponent);
    }

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
            text = baseUrl.origin + normalizeUrlPath(text);
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

        const schemeAuthority = text.match(/^([a-zA-Z][a-zA-Z0-9+.-]*:)\/\//);
        if (schemeAuthority) {
          this._opaque = false;
          this.protocol = schemeAuthority[1].toLowerCase();
          let rest = text.substring(schemeAuthority[0].length);

          // Authority runs up to the first '/', '?' or '#'.
          const authorityEnd = rest.search(/[\/?#]/);
          const authority = authorityEnd < 0 ? rest : rest.substring(0, authorityEnd);
          let afterAuthority = authorityEnd < 0 ? '' : rest.substring(authorityEnd);

          // [userinfo@]host[:port]
          let hostPort = authority;
          const atIndex = authority.lastIndexOf('@');
          if (atIndex >= 0) {
            const userInfo = authority.substring(0, atIndex);
            const colonIndex = userInfo.indexOf(':');
            this.username = colonIndex >= 0 ? userInfo.substring(0, colonIndex) : userInfo;
            this.password = colonIndex >= 0 ? userInfo.substring(colonIndex + 1) : '';
            hostPort = authority.substring(atIndex + 1);
          } else {
            this.username = '';
            this.password = '';
          }

          const hostPortMatch = hostPort.match(/^(\[[^\]]*\]|[^:]*)(?::(\d*))?$/);
          const rawHostname = hostPortMatch ? hostPortMatch[1] : hostPort;
          // WHATWG lowercases the host for special schemes; opaque-host (non-special) keeps case.
          this.hostname = /^(https?|wss?|ftp|file):$/.test(this.protocol) ? rawHostname.toLowerCase() : rawHostname;
          this.port = hostPortMatch && hostPortMatch[2] ? hostPortMatch[2] : '';

          this.hash = '';
          const hashIdx = afterAuthority.indexOf('#');
          if (hashIdx >= 0) {
            this.hash = afterAuthority.substring(hashIdx);
            afterAuthority = afterAuthority.substring(0, hashIdx);
          }
          let query = '';
          const queryIdx = afterAuthority.indexOf('?');
          if (queryIdx >= 0) {
            query = afterAuthority.substring(queryIdx + 1);
            afterAuthority = afterAuthority.substring(0, queryIdx);
          }
          // WHATWG forces an empty path to "/" only for special schemes (http/https/ws/wss/
          // ftp/file). Non-special schemes (e.g. my.custom.protocol://foo) keep an empty path —
          // adding a slash would change Playwright's glob->regex result and break URL matching.
          this.pathname = afterAuthority || (/^(https?|wss?|ftp|file):$/.test(this.protocol) ? '/' : '');
          this.__setQuery(query);
          return;
        }

        // Scheme-only / opaque URLs (data:, about:, mailto:, ...). These have no authority and
        // render without "//"; the opaque path is kept verbatim.
        const schemeMatch = text.match(/^([a-zA-Z][a-zA-Z0-9+.-]*:)([\s\S]*)$/);
        if (!schemeMatch) {
          throw new TypeError('Invalid URL: ' + text);
        }
        this._opaque = true;
        this.protocol = schemeMatch[1].toLowerCase();
        this.username = '';
        this.password = '';
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
        this.__setQuery(query);
      }

      // Stores the raw (already-encoded) query so search/href preserve it verbatim (WHATWG URL
      // query encoding leaves $ _ etc. intact), unlike URLSearchParams form-encoding. Once
      // searchParams is handed out it may be mutated, so search then re-serializes from it.
      __setQuery(query) {
        this._search = query ? '?' + query : '';
        this._searchParams = new global.URLSearchParams(query);
        this._searchDirty = false;
      }

      // Node encodes credentials on assignment (url.password = 'p@ss' gives 'p%40ss'), so href
      // stays parseable; Playwright assigns proxy credentials this way.
      get username() {
        return this._username;
      }

      set username(value) {
        this._username = encodeUserinfo(value);
      }

      get password() {
        return this._password;
      }

      set password(value) {
        this._password = encodeUserinfo(value);
      }

      get host() {
        return this.port ? this.hostname + ':' + this.port : this.hostname;
      }

      get origin() {
        return this._opaque ? 'null' : this.protocol + '//' + this.host;
      }

      get search() {
        if (this._searchDirty) {
          const serialized = this._searchParams.toString();
          return serialized ? '?' + serialized : '';
        }
        return this._search || '';
      }

      set search(value) {
        let text = String(value);
        if (text && text.charAt(0) !== '?') {
          text = '?' + text;
        }
        this._search = text;
        this._searchParams = new global.URLSearchParams(text.replace(/^\?/, ''));
        this._searchDirty = false;
      }

      get searchParams() {
        // Caller may mutate the returned object; subsequent search/href must reflect that.
        this._searchDirty = true;
        return this._searchParams;
      }

      get href() {
        if (this._opaque) {
          // Opaque URLs (data:, about:, ...) have no authority and keep their path verbatim.
          return this.protocol + this.pathname + this.search + (this.hash || '');
        }
        let userInfo = '';
        if (this.username) {
          userInfo = this.username + (this.password ? ':' + this.password : '') + '@';
        }
        const path = this.pathname || (/^(https?|wss?|ftp|file):$/.test(this.protocol) ? '/' : '');
        return this.protocol + '//' + userInfo + this.host + path + this.search + (this.hash || '');
      }

      set href(value) {
        const parsed = new global.URL(String(value));
        this._opaque = parsed._opaque;
        this.protocol = parsed.protocol;
        this.username = parsed.username;
        this.password = parsed.password;
        this.hostname = parsed.hostname;
        this.port = parsed.port;
        this.pathname = parsed.pathname;
        this.hash = parsed.hash;
        this._search = parsed.search;
        this._searchParams = parsed._searchParams;
        this._searchDirty = false;
      }

      toJSON() {
        return this.href;
      }

      toString() {
        return this.href;
      }
    };
  }

  // URL.canParse (used by LocalUtilsDispatcher.connect to validate a ws endpoint). Add it whether
  // URL is our polyfill or a native class that predates canParse.
  if (typeof global.URL.canParse !== 'function') {
    global.URL.canParse = function (url, base) {
      try {
        // eslint-disable-next-line no-new
        new global.URL(String(url), base);
        return true;
      } catch (error) {
        return false;
      }
    };
  }

  // Host-backed TCP server/socket layer for Node net.createServer (Playwright's browser.bind /
  // BrowserServer). The Java host owns the real sockets; events arrive via __playwright4jDrainNet
  // and are dispatched here on the single JS thread.
  const NET_UNIT = '';
  const netServersById = {};
  const netSocketsById = {};

  // A net.Socket on top of the Duplex above, so it carries the stream state bundled libraries read
  // (ws: _readableState.endEmitted, read(), unshift(), _writableState.length) and follows Node's
  // socket lifecycle: allowHalfOpen is false, so the peer's FIN ('end') ends our side too; end()
  // half-closes (shutdownOutput, the peer still sees our FIN while we read); 'close' is emitted
  // once, on a later tick, when the socket is destroyed - which happens by itself once both sides
  // are done, or explicitly via destroy().
  class NetSocket extends Duplex {
    constructor(socketId, connecting) {
      super({ allowHalfOpen: false });
      this.__socketId = socketId;
      this.connecting = !!connecting;
      this.localAddress = undefined;
      this.localPort = undefined;
      this.remoteAddress = undefined;
      this.remotePort = undefined;
    }

    get readyState() {
      if (this.connecting) {
        return 'opening';
      }
      if (this.readable && this.writable) {
        return 'open';
      }
      if (this.readable) {
        return 'readOnly';
      }
      return this.writable ? 'writeOnly' : 'closed';
    }

    get pending() {
      return this.connecting;
    }

    _write(chunk, encoding, callback) {
      if (this.connecting) {
        // Node queues writes until the outbound connection is established.
        const self = this;
        this.once('connect', function () { self._write(chunk, encoding, callback); });
        return;
      }
      if (this.__socketId) {
        const buffer = global.Buffer.isBuffer(chunk)
          ? chunk
          : global.Buffer.from(String(chunk), typeof encoding === 'string' ? encoding : 'utf8');
        host.netServer().write(this.__socketId, buffer.toString('base64'));
      }
      callback();
    }

    _final(callback) {
      if (this.connecting) {
        const self = this;
        this.once('connect', function () { self._final(callback); });
        return;
      }
      if (this.__socketId) {
        host.netServer().shutdownOutput(this.__socketId);
      }
      callback();
    }

    _destroy(error, callback) {
      // A socket destroyed while still connecting is closed when the host reports the outcome.
      if (this.__socketId && !this.connecting) {
        host.netServer().closeSocket(this.__socketId);
        delete netSocketsById[this.__socketId];
      }
      callback(error);
    }

    destroy(error) {
      return destroyStream(this, error, true);
    }

    destroySoon() {
      this.end();
    }

    // Called for a host 'connect' event (net.createConnection).
    __connected(localAddress, localPort, remoteAddress, remotePort) {
      if (this.destroyed) {
        host.netServer().closeSocket(this.__socketId);
        delete netSocketsById[this.__socketId];
        return;
      }
      this.connecting = false;
      this.localAddress = localAddress;
      this.localPort = localPort;
      this.remoteAddress = remoteAddress;
      this.remotePort = remotePort;
      this.emit('connect');
      this.emit('ready');
    }

    // Called for a host 'connecterror' event.
    __connectFailed(error) {
      delete netSocketsById[this.__socketId];
      if (this.destroyed) {
        return;
      }
      this.connecting = false;
      this.destroy(error);
    }

    // Called for a host 'end' event: the peer sent FIN, nothing more will arrive. Like Node's
    // onStreamRead (push(null), then read(0)): 'end' follows once the buffer is empty even if
    // nothing consumes the socket (e.g. the client-certificate proxy's server socket, which only
    // listens for 'close' until the browser sends its first bytes), so it still ends and closes.
    __peerEnded() {
      this.push(null);
      if (this._readableBuffer.length === 0) {
        this._scheduleEnd();
      }
    }

    // Called for a host 'close' event: reading failed (connection reset, or a failed write closed
    // the socket). Local closes never get here, the socket is unregistered first. Like Node:
    // 'error' (ECONNRESET), then 'close', no 'end'; anything still buffered stays readable. The
    // error is only emitted to existing listeners, an unhandled one must not take the process down.
    // A socket wrapped by tls.connect does not see the read error in Node, its TLSSocket does (the
    // client-certificate proxy answers the browser with a 503 page from that TLSSocket's 'error').
    __hostClosed() {
      const error = new Error('read ECONNRESET');
      error.code = 'ECONNRESET';
      error.errno = -104;
      error.syscall = 'read';
      if (this.__tlsSocket) {
        this.__tlsSocket.__readFailed(error);
        this.destroy();
      } else if (this.listenerCount('error') > 0) {
        this.destroy(error);
      } else {
        this.destroy();
      }
    }

    setEncoding() { return this; }
    setNoDelay(noDelay) {
      const enable = noDelay === undefined ? true : !!noDelay;
      if (this.connecting) {
        this.once('connect', () => this.setNoDelay(enable));
      } else if (this.__socketId && !this.destroyed) {
        host.netServer().setNoDelay(this.__socketId, enable);
      }
      return this;
    }
    setKeepAlive() { return this; }
    setTimeout() { return this; }
    ref() { return this; }
    unref() { return this; }
    address() {
      return { address: this.localAddress, port: this.localPort, family: 'IPv4' };
    }
  }

  class NetServer extends EventEmitter {
    constructor(connectionListener) {
      super();
      this.__serverId = null;
      this.__port = 0;
      this.__host = '127.0.0.1';
      if (typeof connectionListener === 'function') {
        this.on('connection', connectionListener);
      }
    }

    listen() {
      const args = Array.prototype.slice.call(arguments);
      const callback = typeof args[args.length - 1] === 'function' ? args.pop() : null;
      let port = 0;
      let hostName = '127.0.0.1';
      if (args.length > 0 && args[0] && typeof args[0] === 'object') {
        port = args[0].port || 0;
        hostName = args[0].host || '127.0.0.1';
      } else {
        if (args.length > 0 && args[0] !== undefined && args[0] !== null) {
          port = parseInt(args[0], 10) || 0;
        }
        if (args.length > 1 && typeof args[1] === 'string') {
          hostName = args[1];
        }
      }
      const result = String(host.netServer().listen(hostName, port));
      const separatorIndex = result.indexOf(NET_UNIT);
      this.__serverId = separatorIndex < 0 ? result : result.substring(0, separatorIndex);
      this.__port = separatorIndex < 0 ? port : parseInt(result.substring(separatorIndex + 1), 10);
      this.__host = hostName;
      netServersById[this.__serverId] = this;
      if (callback) {
        this.on('listening', callback);
      }
      const self = this;
      Promise.resolve().then(function () { self.emit('listening'); });
      return this;
    }

    address() {
      return { port: this.__port, address: this.__host, family: 'IPv4' };
    }

    close(callback) {
      if (this.__serverId) {
        host.netServer().closeServer(this.__serverId);
        delete netServersById[this.__serverId];
        this.__serverId = null;
      }
      const self = this;
      Promise.resolve().then(function () { self.emit('close'); });
      if (callback) {
        callback();
      }
      return this;
    }

    unref() { return this; }
    ref() { return this; }
  }

  function dispatchNetEvent(parts) {
    const type = parts[0];
    if (type === 'connection') {
      const server = netServersById[parts[1]];
      const socket = new NetSocket(parts[2], false);
      netSocketsById[parts[2]] = socket;
      if (server) {
        server.emit('connection', socket);
      } else {
        socket.destroy();
      }
      return;
    }
    const socket = netSocketsById[parts[1]];
    if (!socket) {
      // Already destroyed on our side; the host socket is closed.
      return;
    }
    if (type === 'data') {
      socket.push(global.Buffer.from(parts[2] || '', 'base64'));
    } else if (type === 'end') {
      socket.__peerEnded();
    } else if (type === 'close') {
      socket.__hostClosed();
    } else if (type === 'connect') {
      socket.__connected(parts[2], parseInt(parts[3], 10), parts[4], parseInt(parts[5], 10));
    } else if (type === 'connecterror') {
      const error = new Error(parts[2] || 'connect failed');
      error.code = 'ECONNREFUSED';
      socket.__connectFailed(error);
    }
  }

  global.__playwright4jDrainNet = function () {
    const batch = String(host.netServer().drainEvents() || '');
    if (batch.length === 0) {
      return 0;
    }
    const lines = batch.split('\n');
    for (let index = 0; index < lines.length; index++) {
      const parts = lines[index].split(NET_UNIT);
      // Listeners run synchronously in here. In Node an exception thrown by one would terminate
      // the process; here it must not abort the rest of the batch, i.e. the other connections.
      try {
        dispatchNetEvent(parts);
      } catch (error) {
        reportUncaughtException(error, 'net ' + parts[0] + ' event');
      }
    }
    return lines.length;
  };

  // Diagnostics for leak checks: sockets still registered for host events.
  global.__playwright4jOpenNetSockets = function () {
    return Object.keys(netSocketsById).length;
  };

  modules.net = {
    Socket: NetSocket,
    Server: NetServer,
    createServer: function (options, connectionListener) {
      if (typeof options === 'function') {
        connectionListener = options;
      }
      return new NetServer(connectionListener);
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
    createConnection: function (options, connectListener) {
      let hostName = '127.0.0.1';
      let port = 0;
      if (options && typeof options === 'object') {
        hostName = options.host || options.hostname || '127.0.0.1';
        port = options.port || 0;
      } else {
        port = parseInt(options, 10) || 0;
        if (typeof connectListener === 'string') {
          hostName = connectListener;
          connectListener = arguments[2];
        }
      }
      const socketId = String(host.netServer().connect(String(hostName), parseInt(port, 10) || 0));
      const socket = new NetSocket(socketId, true);
      netSocketsById[socketId] = socket;
      if (typeof connectListener === 'function') {
        socket.on('connect', connectListener);
      }
      return socket;
    }
  };
  modules.net.connect = modules.net.createConnection;

  function indexOfDoubleCRLF(buffer) {
    for (let index = 0; index + 3 < buffer.length; index++) {
      if (buffer[index] === 13 && buffer[index + 1] === 10 && buffer[index + 2] === 13 && buffer[index + 3] === 10) {
        return index;
      }
    }
    return -1;
  }

  function parseHttpRequestHead(headerText, socket) {
    const lines = headerText.split('\r\n');
    const requestLine = (lines[0] || '').split(' ');
    const headers = {};
    const rawHeaders = [];
    for (let index = 1; index < lines.length; index++) {
      const line = lines[index];
      if (!line) {
        continue;
      }
      const colon = line.indexOf(':');
      if (colon < 0) {
        continue;
      }
      const name = line.substring(0, colon).trim();
      const value = line.substring(colon + 1).trim();
      rawHeaders.push(name, value);
      headers[name.toLowerCase()] = value;
    }
    const request = new EventEmitter();
    request.method = requestLine[0] || 'GET';
    request.url = requestLine[1] || '/';
    request.httpVersion = (requestLine[2] || 'HTTP/1.1').replace('HTTP/', '');
    request.headers = headers;
    request.rawHeaders = rawHeaders;
    request.socket = socket;
    request.connection = socket;
    return request;
  }

  function createServerResponse(socket) {
    const response = new EventEmitter();
    response.statusCode = 200;
    response.headersSent = false;
    const headers = {};
    response.setHeader = function (name, value) { headers[String(name).toLowerCase()] = value; return this; };
    response.getHeader = function (name) { return headers[String(name).toLowerCase()]; };
    response.writeHead = function (statusCode, statusMessageOrHeaders, maybeHeaders) {
      response.statusCode = statusCode;
      const extra = typeof statusMessageOrHeaders === 'object' ? statusMessageOrHeaders : maybeHeaders;
      if (extra) {
        for (const key of Object.keys(extra)) {
          headers[key.toLowerCase()] = extra[key];
        }
      }
      return this;
    };
    let wroteHead = false;
    const flushHead = function () {
      if (wroteHead) {
        return;
      }
      wroteHead = true;
      response.headersSent = true;
      let head = 'HTTP/1.1 ' + response.statusCode + '\r\n';
      for (const key of Object.keys(headers)) {
        head += key + ': ' + headers[key] + '\r\n';
      }
      head += '\r\n';
      socket.write(global.Buffer.from(head, 'latin1'));
    };
    response.write = function (chunk) {
      flushHead();
      socket.write(global.Buffer.isBuffer(chunk) ? chunk : global.Buffer.from(String(chunk)));
      return true;
    };
    response.end = function (chunk) {
      flushHead();
      if (chunk !== undefined && chunk !== null) {
        socket.write(global.Buffer.isBuffer(chunk) ? chunk : global.Buffer.from(String(chunk)));
      }
      response.emit('finish');
      return this;
    };
    return response;
  }

  // Minimal HTTP server backing http.createServer. Listen/address/close are real (delegated to a
  // host TCP server). Incoming connections are parsed just far enough to dispatch 'request' and, for
  // WebSocket clients, 'upgrade' (request, socket, head) — the bundled ws library then performs the
  // handshake/framing over the raw socket (used by launch-server's WSServer).
  function createHttpServer(requestListener) {
    const netServer = new NetServer();
    const server = new EventEmitter();
    server.__netServer = netServer;
    if (typeof requestListener === 'function') {
      server.on('request', requestListener);
    }
    netServer.on('connection', function (socket) {
      // Like Node's http server (socketOnError): a socket error before the request was dispatched
      // destroys the socket. ws attaches its own handler after the upgrade.
      const onError = function () {
        socket.destroy();
      };
      socket.on('error', onError);
      // Node's http server defaults to noDelay (small frames must not wait for delayed ACKs).
      socket.setNoDelay(true);
      server.emit('connection', socket);
      let buffer = global.Buffer.alloc(0);
      const onData = function (chunk) {
        buffer = global.Buffer.concat([buffer, chunk]);
        const headerEnd = indexOfDoubleCRLF(buffer);
        if (headerEnd < 0) {
          return; // headers not complete yet
        }
        socket.removeListener('data', onData);
        const request = parseHttpRequestHead(buffer.subarray(0, headerEnd).toString('latin1'), socket);
        const head = buffer.subarray(headerEnd + 4);
        // Without an 'upgrade' listener Node (since v10) dispatches the request as a normal one.
        if (String(request.headers['upgrade'] || '').toLowerCase() === 'websocket' && server.listenerCount('upgrade') > 0) {
          // Like Node's http server: the socket is handed over paused, so bytes arriving before
          // the new owner (ws) attaches its 'data' listener are buffered, not dropped.
          socket.readableFlowing = null;
          socket.removeListener('error', onError);
          server.emit('upgrade', request, socket, head);
        } else {
          server.emit('request', request, createServerResponse(socket));
        }
      };
      socket.on('data', onData);
    });
    server.listen = function () {
      const args = Array.prototype.slice.call(arguments);
      const callback = typeof args[args.length - 1] === 'function' ? args.pop() : null;
      const self = this;
      if (callback) {
        this.once('listening', callback);
      }
      netServer.once('listening', function () { self.emit('listening'); });
      netServer.listen.apply(netServer, args);
      return this;
    };
    server.address = function () {
      return netServer.address();
    };
    server.close = function (callback) {
      const self = this;
      netServer.once('close', function () { self.emit('close'); });
      netServer.close(callback);
      return this;
    };
    server.setTimeout = function () { return this; };
    server.ref = function () { return this; };
    server.unref = function () { return this; };
    return server;
  }

  modules.http.createServer = function (options, requestListener) {
    return createHttpServer(typeof options === 'function' ? options : requestListener);
  };
  modules.http.Server = function (options, requestListener) {
    return createHttpServer(typeof options === 'function' ? options : requestListener);
  };
  modules.https.createServer = function (options, requestListener) {
    return createHttpServer(typeof options === 'function' ? options : requestListener);
  };

  // Host-backed TLS (via SSLEngine) for Playwright's BrowserContext client-certificate proxy
  // (socksClientCertificatesInterceptor.js). Each TLSSocket bridges an underlying byte stream
  // (a net socket, or the SOCKS Duplex on the browser side) and a host SSLEngine: bytes from the
  // peer drive the handshake / are decrypted; writes are encrypted back onto the underlying stream.
  function pemString(value) {
    if (value === undefined || value === null) {
      return '';
    }
    return global.Buffer.isBuffer(value) ? value.toString('utf8') : String(value);
  }

  function makeTlsSocket(underlying, engineId, onSecure) {
    const socket = new Duplex({
      // As Node's TLSSocket: half-open like the stream it wraps (a net socket is not).
      allowHalfOpen: !!(underlying && underlying.allowHalfOpen),
      read: function () {},
      write: function (chunk, encoding, callback) {
        try {
          const res = JSON.parse(host.tls().wrap(engineId, toInputBuffer(chunk).toString('base64')));
          if (res.net) {
            underlying.write(global.Buffer.from(res.net, 'base64'));
          }
          callback(res.error ? new Error(res.error) : undefined);
        } catch (error) {
          callback(error);
        }
      },
      // Node's TLS shutdown on end(): once everything written is out, send close_notify and end
      // the wrapped stream, so the peer sees the TLS stream end (the client-certificate proxy ends
      // the browser's connection this way after its 503 page). Before the handshake completed
      // there is no close_notify to send (as with OpenSSL), only the wrapped stream ends.
      final: function (callback) {
        let failure;
        try {
          if (secured) {
            const res = JSON.parse(host.tls().shutdown(engineId));
            if (res.error) {
              failure = new Error(res.error);
            } else if (res.net) {
              underlying.write(global.Buffer.from(res.net, 'base64'));
            }
          }
          if (typeof underlying.end === 'function' && !underlying.destroyed) {
            underlying.end();
          }
        } catch (error) {
          failure = failure || error;
        }
        callback(failure);
      },
      destroy: function (error, callback) {
        host.tls().closeEngine(engineId);
        try {
          if (underlying && typeof underlying.destroy === 'function') {
            underlying.destroy();
          }
        } catch (ignored) {
          // best effort
        }
        callback(error);
      }
    });
    socket.encrypted = true;
    socket.authorized = true;
    socket.alpnProtocol = false;
    let secured = false;

    // A per-connection TLS failure must not crash the shared driver: only emit 'error' if someone
    // is listening (an unhandled 'error' would throw out of the pump), then tear down: the socket is
    // destroyed (engine and transport released, 'close' emitted) like Node's TLSSocket. No 'end':
    // an errored TLSSocket does not emit it, and a pipe would end the other side before the
    // client-certificate proxy writes its error page there.
    const fail = function (error) {
      socket.destroy(socket.listenerCount('error') > 0 ? error : undefined);
    };

    const process = function (res) {
      if (res.error) {
        fail(new Error(res.error));
        return;
      }
      if (res.net) {
        underlying.write(global.Buffer.from(res.net, 'base64'));
      }
      if (res.app) {
        socket.push(global.Buffer.from(res.app, 'base64'));
      }
      if (res.established && !secured) {
        secured = true;
        socket.alpnProtocol = res.alpn ? res.alpn : false;
        if (onSecure) {
          onSecure(socket);
        }
      }
      if (res.closed) {
        socket.push(null);
      }
    };
    const feed = function (base64) {
      try {
        process(JSON.parse(host.tls().pump(engineId, base64 || '')));
      } catch (error) {
        fail(error);
      }
    };

    underlying.on('data', function (data) { feed(toInputBuffer(data).toString('base64')); });
    // The transport is gone: end the decrypted side and destroy the TLS socket (engine and
    // underlying stream released, 'close' emitted once).
    underlying.on('close', function () { socket.push(null); socket.destroy(); });
    underlying.on('error', function (error) { fail(error); });
    socket.__kickHandshake = function () { feed(''); };
    socket.__readFailed = fail;
    return socket;
  }

  modules.tls = {
    createSecureContext: function (options) {
      return { __tlsOptions: extractTlsOptions(options || {}) };
    },
    connect: function (options, callback) {
      const underlying = options.socket;
      const secureContext = options.secureContext;
      const tlsOptions = secureContext && secureContext.__tlsOptions ? secureContext.__tlsOptions : '';
      const rejectUnauthorized = options.rejectUnauthorized !== false;
      const alpn = (options.ALPNProtocols || []).join(',');
      const servername = options.servername || '';
      const engineId = String(host.tls().newClientEngine(tlsOptions, String(servername), alpn, rejectUnauthorized));
      const socket = makeTlsSocket(underlying, engineId, function (s) { s.emit('secureConnect'); });
      if (underlying instanceof NetSocket) {
        // Read errors of the TCP connection belong to the TLSSocket (see NetSocket.__hostClosed).
        underlying.__tlsSocket = socket;
      }
      if (typeof callback === 'function') {
        socket.on('secureConnect', callback);
      }
      // Drive the client handshake (emit ClientHello) once listeners are attached.
      Promise.resolve().then(function () { socket.__kickHandshake(); });
      return socket;
    },
    createServer: function (options, secureConnectionListener) {
      const server = new EventEmitter();
      const keyPem = pemString(options.key);
      const certPem = pemString(options.cert);
      const alpn = (options.ALPNProtocols || []).join(',');
      if (typeof secureConnectionListener === 'function') {
        server.on('secureConnection', secureConnectionListener);
      }
      server.on('connection', function (underlying) {
        // A stream that is already gone never completes a handshake; do not leave an engine behind.
        if (underlying.destroyed) {
          return;
        }
        let engineId;
        try {
          engineId = String(host.tls().newServerEngine(keyPem, certPem, alpn));
        } catch (error) {
          server.emit('error', error);
          return;
        }
        makeTlsSocket(underlying, engineId, function (s) { server.emit('secureConnection', s); });
        // The browser's ClientHello is already buffered on `underlying`; attaching the 'data'
        // listener in makeTlsSocket resumes it and drives the server handshake.
      });
      server.close = function (callback) {
        if (callback) {
          callback();
        }
        return this;
      };
      server.listen = function () { return this; };
      return server;
    }
  };
  modules.url = {
    URL: global.URL,
    URLSearchParams: global.URLSearchParams
  };

  // process.env backed by the host environment, enumerable ({...process.env}, Object.keys) so a
  // spawn that passes it on keeps the inherited variables, and writable like Node's (assignments
  // and deletes stay local to this runtime). Names are case-insensitive on Windows, as in Node.
  function createProcessEnv() {
    const caseInsensitive = host.environment().platform() === 'win32';
    const overrides = {};
    const deleted = {};
    function key(name) {
      return caseInsensitive ? String(name).toUpperCase() : String(name);
    }
    function hostNames() {
      const names = String(host.environment().environmentNames());
      return names ? names.split(ARGUMENT_SEPARATOR) : [];
    }
    function names() {
      const seen = {};
      const result = [];
      hostNames().concat(Object.keys(overrides).map(function (k) { return overrides[k].name; })).forEach(function (name) {
        const k = key(name);
        if (!seen[k] && !deleted[k]) {
          seen[k] = true;
          result.push(Object.prototype.hasOwnProperty.call(overrides, k) ? overrides[k].name : name);
        }
      });
      return result;
    }
    function has(name) {
      const k = key(name);
      if (Object.prototype.hasOwnProperty.call(overrides, k)) {
        return true;
      }
      if (deleted[k]) {
        return false;
      }
      return hostNames().some(function (hostName) { return key(hostName) === k; });
    }
    function value(name) {
      const k = key(name);
      if (Object.prototype.hasOwnProperty.call(overrides, k)) {
        return overrides[k].value;
      }
      if (deleted[k]) {
        return undefined;
      }
      return host.environment().getEnvironmentValue(String(name));
    }
    return new Proxy({}, {
      get: function (target, name) {
        if (typeof name === 'symbol') {
          return undefined;
        }
        return value(name);
      },
      set: function (target, name, newValue) {
        const k = key(name);
        delete deleted[k];
        overrides[k] = { name: String(name), value: String(newValue) };
        return true;
      },
      deleteProperty: function (target, name) {
        const k = key(name);
        delete overrides[k];
        deleted[k] = true;
        return true;
      },
      has: function (target, name) {
        return typeof name !== 'symbol' && has(name);
      },
      ownKeys: function () {
        return names();
      },
      getOwnPropertyDescriptor: function (target, name) {
        if (typeof name === 'symbol' || !has(name)) {
          return undefined;
        }
        return { value: value(name), writable: true, enumerable: true, configurable: true };
      }
    });
  }

  global.process = new EventEmitter();
  Object.assign(global.process, {
    env: createProcessEnv(),
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
        // In --node-compat mode (e.g. cli.js launch-server) real stdout carries the ws:// endpoint
        // line the caller reads, so route it to the host's raw stdout. In the normal driver mode
        // stdout is the length-prefixed protocol channel, so process.stdout must go to stderr.
        if (global.__playwright4jNodeCompat) {
          return host.driverPipe().writeOut(String(chunk));
        }
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
    exit: function (code) {
      // Record the requested exit for the --node-compat pump (e.g. cli.js launch-server). In the
      // normal driver-pipe mode nothing reads these, so exit stays a no-op there (the driver JVM
      // must not die mid-command).
      global.__playwright4jExitCode = (code === undefined || code === null) ? 0 : (code | 0);
      global.__playwright4jExitRequested = true;
      return undefined;
    },
    kill: function (pid, signal) {
      return killChildProcessByPid(pid, signal);
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

    static allocUnsafe(size) {
      return new Buffer(size);
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

    slice(start, end) {
      return Buffer.from(this.subarray(start, end));
    }

    // Copies bytes from this buffer into target (Node Buffer.copy). Used by the zip writer to
    // assemble local/central-directory/EOCD records; a missing copy() previously threw inside a
    // swallowed setImmediate callback and wedged HAR zip finalization.
    copy(target, targetStart, sourceStart, sourceEnd) {
      targetStart = targetStart || 0;
      sourceStart = sourceStart || 0;
      sourceEnd = sourceEnd === undefined ? this.length : sourceEnd;
      let count = 0;
      for (let index = sourceStart; index < sourceEnd && targetStart + count < target.length; index++) {
        target[targetStart + count] = this[index];
        count++;
      }
      return count;
    }

    // Writes a string into this buffer at offset (Node Buffer.write).
    write(string, offset, length, encoding) {
      if (typeof offset === 'string') {
        encoding = offset;
        offset = 0;
        length = undefined;
      } else if (typeof length === 'string') {
        encoding = length;
        length = undefined;
      }
      offset = offset || 0;
      const bytes = Buffer.from(String(string), encoding || 'utf8');
      const writable = length === undefined ? bytes.length : Math.min(length, bytes.length);
      let count = 0;
      for (let index = 0; index < writable && offset + count < this.length; index++) {
        this[offset + count] = bytes[index];
        count++;
      }
      return count;
    }

    // Fixed-width integer accessors (Node Buffer API) used by Playwright's zip writer/reader.
    writeUInt8(value, offset) {
      offset = offset || 0;
      this[offset] = value & 0xff;
      return offset + 1;
    }

    writeUInt16LE(value, offset) {
      offset = offset || 0;
      this[offset] = value & 0xff;
      this[offset + 1] = (value >>> 8) & 0xff;
      return offset + 2;
    }

    writeUInt16BE(value, offset) {
      offset = offset || 0;
      this[offset] = (value >>> 8) & 0xff;
      this[offset + 1] = value & 0xff;
      return offset + 2;
    }

    writeUInt32LE(value, offset) {
      offset = offset || 0;
      this[offset] = value & 0xff;
      this[offset + 1] = (value >>> 8) & 0xff;
      this[offset + 2] = (value >>> 16) & 0xff;
      this[offset + 3] = (value >>> 24) & 0xff;
      return offset + 4;
    }

    writeUInt32BE(value, offset) {
      offset = offset || 0;
      this[offset] = (value >>> 24) & 0xff;
      this[offset + 1] = (value >>> 16) & 0xff;
      this[offset + 2] = (value >>> 8) & 0xff;
      this[offset + 3] = value & 0xff;
      return offset + 4;
    }

    writeInt8(value, offset) { return this.writeUInt8(value, offset); }
    writeInt16LE(value, offset) { return this.writeUInt16LE(value, offset); }
    writeInt16BE(value, offset) { return this.writeUInt16BE(value, offset); }
    writeInt32LE(value, offset) { return this.writeUInt32LE(value, offset); }
    writeInt32BE(value, offset) { return this.writeUInt32BE(value, offset); }

    writeBigUInt64LE(value, offset) {
      offset = offset || 0;
      let v = typeof value === 'bigint' ? value : BigInt(value);
      for (let index = 0; index < 8; index++) {
        this[offset + index] = Number(v & 0xffn);
        v >>= 8n;
      }
      return offset + 8;
    }

    writeUIntLE(value, offset, byteLength) {
      offset = offset || 0;
      let v = value;
      for (let index = 0; index < byteLength; index++) {
        this[offset + index] = v & 0xff;
        v = Math.floor(v / 256);
      }
      return offset + byteLength;
    }

    // Big-endian multi-byte write. The bundled `ws` Sender frames any payload >= 65536 bytes with a
    // 64-bit length field written via buf.writeUIntBE(length, 4, 6); without this method every
    // WebSocket frame >= 64 KiB (e.g. a video/trace artifact chunk read back over browserType.connect)
    // throws in Sender.frame and is silently never sent, hanging the client on the read response.
    writeUIntBE(value, offset, byteLength) {
      offset = offset || 0;
      let v = value;
      for (let index = byteLength - 1; index >= 0; index--) {
        this[offset + index] = v & 0xff;
        v = Math.floor(v / 256);
      }
      return offset + byteLength;
    }

    writeBigUInt64BE(value, offset) {
      offset = offset || 0;
      let v = typeof value === 'bigint' ? value : BigInt(value);
      for (let index = 7; index >= 0; index--) {
        this[offset + index] = Number(v & 0xffn);
        v >>= 8n;
      }
      return offset + 8;
    }

    readUInt8(offset) {
      return this[offset || 0];
    }

    readUInt16LE(offset) {
      offset = offset || 0;
      return this[offset] | (this[offset + 1] << 8);
    }

    readUInt16BE(offset) {
      offset = offset || 0;
      return (this[offset] << 8) | this[offset + 1];
    }

    readUInt32LE(offset) {
      offset = offset || 0;
      return (this[offset] | (this[offset + 1] << 8) | (this[offset + 2] << 16)) + this[offset + 3] * 0x1000000;
    }

    readUInt32BE(offset) {
      offset = offset || 0;
      return this[offset] * 0x1000000 + ((this[offset + 1] << 16) | (this[offset + 2] << 8) | this[offset + 3]);
    }

    readInt32LE(offset) {
      offset = offset || 0;
      return this[offset] | (this[offset + 1] << 8) | (this[offset + 2] << 16) | (this[offset + 3] << 24);
    }

    readBigUInt64LE(offset) {
      offset = offset || 0;
      let result = 0n;
      for (let index = 7; index >= 0; index--) {
        result = (result << 8n) | BigInt(this[offset + index]);
      }
      return result;
    }

    readUIntLE(offset, byteLength) {
      offset = offset || 0;
      let value = 0;
      let multiplier = 1;
      for (let index = 0; index < byteLength; index++) {
        value += this[offset + index] * multiplier;
        multiplier *= 256;
      }
      return value;
    }
  }

  global.Buffer = Buffer;

  modules.buffer = {
    Buffer: global.Buffer
  };

  // Base64 <-> binary-string globals used by Playwright's HAR content decoding.
  if (typeof global.atob !== 'function') {
    global.atob = function (data) {
      return latin1Decode(base64Decode(String(data)));
    };
  }
  if (typeof global.btoa !== 'function') {
    global.btoa = function (data) {
      return base64Encode(latin1Encode(String(data)));
    };
  }

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

  // Resolves a bare "playwright-core/<rel>" specifier to a bundled package resource, or null.
  function resolvePackageModule(name) {
    if (!global.__playwright4jDriverBundleSource) {
      return null;
    }
    let relative = null;
    if (name.indexOf('playwright-core/') === 0) {
      relative = name.substring('playwright-core/'.length);
    } else if (name === 'playwright-core') {
      relative = 'index';
    } else {
      return null;
    }
    const base = normalizeResourceName(global.__playwright4jDriverBundleSource.packageResourceName(relative));
    const hasExplicitExtension = /\.[^/]+$/.test(base);
    const candidates = hasExplicitExtension
      ? [base]
      : [base + '.js', base + '.json', base + '/index.js', base];
    for (let index = 0; index < candidates.length; index++) {
      if (global.__playwright4jDriverBundleSource.hasResource(candidates[index])) {
        return candidates[index];
      }
    }
    return null;
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
          pw4jDebug('Driver PipeTransport attached');
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

        pw4jDebug('Driver protocol OUT ' + String(message).substring(0, 160));
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
        pw4jDebug('Driver protocol IN ' + String(message).substring(0, 160));
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
        this.__closed = false;
        // One message per drain so the guest fully drains microtasks between CDP messages
        // (correct event ordering), and non-blocking send (responses arrive via drain),
        // mirroring the browser pipe transport.
        this._inbox = [];
      }

      static async connect(progress, url, options = {}) {
        progress?.log('<ws connecting> ' + url);
        traceWebSocket('transport-connect:' + url);
        // Forward caller-supplied handshake headers (e.g. browserType.connect/connectOverCDP
        // headers: a custom User-Agent or x-playwright-* headers) into the host WebSocket open.
        const separator = String.fromCharCode(30);
        const headerPairs = [];
        const headers = options && options.headers;
        if (headers) {
          for (const name of Object.keys(headers)) {
            const value = headers[name];
            if (value === undefined || value === null) {
              continue;
            }
            headerPairs.push(String(name), String(value));
          }
        }
        const connectionId = host.webSocketClient().open(String(url), headerPairs.join(separator));
        progress?.log('<ws connected> ' + url);
        traceWebSocket('transport-connected:' + url);
        const transport = new HostBackedWebSocketTransport(String(url), connectionId);
        activeTransports.push(transport);
        return transport;
      }

      send(message) {
        // Like ws (WebSocketTransport.send without a callback), a send on a closed socket is dropped:
        // Playwright learns about the close through onclose, which drain() surfaces once the host
        // reports isClosed(), so 'disconnected' is emitted and the pending call rejects with the
        // close reason instead of an I/O error.
        if (this.__closed) {
          return;
        }
        traceWebSocket('transport-send:' + JSON.stringify(message));
        try {
          host.webSocketClient().send(this.connectionId, JSON.stringify(message));
        } catch (error) {
          if (!host.webSocketClient().isClosed(this.connectionId)) {
            throw error;
          }
          traceWebSocket('transport-send-dropped:' + (error && error.message));
        }
      }

      drain() {
        if (this._inbox.length === 0) {
          String(host.webSocketClient().drain(this.connectionId, 5) || '').split('').forEach((message) => {
            if (message) {
              this._inbox.push(message);
            }
          });
        }
        if (this._inbox.length === 0) {
          // No pending messages: if the peer closed the connection (server closed / killed), surface
          // onclose exactly once so Playwright fires the browser 'disconnected' event and pending
          // operations reject instead of hanging.
          if (!this.__closed && host.webSocketClient().isClosed(this.connectionId)) {
            this.__closed = true;
            this.close(host.webSocketClient().closeReason(this.connectionId));
            return 1;
          }
          return 0;
        }
        const message = this._inbox.shift();
        // A malformed / non-CDP message (e.g. connecting to a plain WebSocket server that is not a
        // CDP endpoint, as TestChromium.shouldSendExtraHeadersWithConnectRequest does) must not let
        // a JSON.parse / onmessage error escape this transport and crash the shared driver pump
        // (which would take down the whole connection). A malformed frame is a fatal protocol error
        // for this connection: close the transport so the pending connect rejects promptly, instead
        // of either crashing the driver or hanging until the caller's timeout.
        if (this.onmessage) {
          let parsed;
          try {
            parsed = JSON.parse(message);
          } catch (error) {
            traceWebSocket('transport-message-error:' + (error && error.message));
            this.close();
            return 1;
          }
          this.onmessage(parsed);
        }
        return 1;
      }

      close(reason) {
        this.__closed = true;
        const index = activeTransports.indexOf(this);
        if (index >= 0) {
          activeTransports.splice(index, 1);
        }
        host.webSocketClient().close(this.connectionId);

        if (this.onclose) {
          // Pass the peer's close reason through (e.g. "Browser has been closed") so pending
          // operations reject with the real cause; empty/undefined lets Playwright pick its default.
          this.onclose(reason ? reason : undefined);
        }
      }

      async closeAndWait() {
        this.close();
      }
    };
  }

  // playwright4j.bidi.naturalPointer (Firefox over BiDi, off by default): Playwright moves the
  // mouse to the target in one jump and presses and releases it at once (#43). With the property,
  // a move without the caller's `steps` follows a slightly curved path with a minimum-jerk speed
  // profile and a duration that grows with the distance (Fitts' law), and a click without the
  // caller's `delay` pauses before the press, holds the button and pauses between clicks.
  const NATURAL_POINTER = {
    // Fitts' law T = a + b * log2(1 + D / W) for a target width W.
    moveBaseMillis: 90, moveMillisPerBit: 90, targetWidth: 24,
    segmentMillis: 16, maxSegments: 60, maxCurve: 0.12, jitter: 0.15,
    settleMillis: [30, 90], holdMillis: [55, 130], betweenClicksMillis: [90, 170]
  };

  function naturalPointer() {
    return host.naturalPointer() === true;
  }

  function randomBetween(range) {
    return range[0] + Math.random() * (range[1] - range[0]);
  }

  // The intermediate points from (fromX, fromY) to (x, y); the last one is the target itself.
  function naturalPointerPath(fromX, fromY, x, y) {
    const distance = Math.hypot(x - fromX, y - fromY);
    if (distance < 1) {
      return [{ x: x, y: y, duration: 0 }];
    }
    const config = NATURAL_POINTER;
    const millis = (config.moveBaseMillis + config.moveMillisPerBit * Math.log2(1 + distance / config.targetWidth))
      * (1 + (Math.random() * 2 - 1) * config.jitter);
    const segments = Math.max(2, Math.min(config.maxSegments, Math.round(millis / config.segmentMillis)));
    const duration = Math.round(millis / segments);
    // A quadratic Bezier whose control point lies off the straight line, on a random side.
    const bend = (Math.random() * 2 - 1) * config.maxCurve * distance;
    const controlX = (fromX + x) / 2 - (y - fromY) / distance * bend;
    const controlY = (fromY + y) / 2 + (x - fromX) / distance * bend;
    const points = [];
    for (let i = 1; i <= segments; i++) {
      const t = i / segments;
      const s = t * t * t * (10 - 15 * t + 6 * t * t);
      const u = 1 - s;
      points.push(i === segments ? { x: x, y: y, duration: duration } : {
        x: Math.round(u * u * fromX + 2 * u * s * controlX + s * s * x),
        y: Math.round(u * u * fromY + 2 * u * s * controlY + s * s * y),
        duration: duration
      });
    }
    return points;
  }

  function installNaturalPointer(bidiPage) {
    const mouse = bidiPage._page.mouse;
    const originalMove = mouse.move;
    const originalClick = mouse.click;
    mouse.move = async function (progress, x, y, options) {
      options = options || {};
      if (!naturalPointer() || options.steps !== undefined) {
        return originalMove.call(this, progress, x, y, options);
      }
      const path = naturalPointerPath(this._x, this._y, x, y);
      this._x = x;
      this._y = y;
      // One action sequence on Playwright's own pointer source, so pressed buttons carry over.
      await progress.race(bidiPage._session.send('input.performActions', {
        context: bidiPage._session.sessionId,
        actions: [{
          type: 'pointer',
          id: 'pw_mouse',
          parameters: { pointerType: 'mouse' },
          actions: path.map(function (point) {
            return { type: 'pointerMove', x: point.x, y: point.y, duration: point.duration };
          })
        }]
      }));
    };
    mouse.click = async function (progress, x, y, options) {
      options = options || {};
      if (!naturalPointer() || options.delay !== undefined) {
        return originalClick.call(this, progress, x, y, options);
      }
      const clickCount = options.clickCount || 1;
      await this.move(progress, x, y, { forClick: true, steps: options.steps });
      await progress.wait(randomBetween(NATURAL_POINTER.settleMillis));
      for (let cc = 1; cc <= clickCount; ++cc) {
        await this.down(progress, Object.assign({}, options, { clickCount: cc }));
        await progress.wait(randomBetween(NATURAL_POINTER.holdMillis));
        await this.up(progress, Object.assign({}, options, { clickCount: cc }));
        if (cc < clickCount) {
          await progress.wait(randomBetween(NATURAL_POINTER.betweenClicksMillis));
        }
      }
    };
  }

  function patchBidiPageNaturalPointer(BidiPage) {
    const originalInitialize = BidiPage.prototype._initialize;
    BidiPage.prototype._initialize = function () {
      if (isBidiFirefox(this._browserContext._browser)) {
        installNaturalPointer(this);
      }
      return originalInitialize.apply(this, arguments);
    };
  }

  // Firefox sends no browsingContext.load for the initial about:blank of a top-level context it
  // creates (browsingContext.create), so its main frame only ever reaches "commit". The persistent
  // launch (launchPersistentContext) waits for "load" on that default page and would hang until its
  // timeout. A fresh, opener-less about:blank page with no navigation pending is fully loaded, so
  // report domcontentloaded/load for it once the page is initialized.
  function patchBidiPageInitialBlankLoad(BidiPage) {
    const originalInitialize = BidiPage.prototype._initialize;
    BidiPage.prototype._initialize = async function () {
      await originalInitialize.apply(this, arguments);
      const frame = this._page.mainFrame();
      if (this._opener || !frame || frame.pendingDocument() || frame._url !== 'about:blank') {
        return;
      }
      this._page.frameManager.frameLifecycleEvent(frame._id, 'domcontentloaded');
      this._page.frameManager.frameLifecycleEvent(frame._id, 'load');
    };
  }

  // BidiPage._createUtilityWorld evaluates at {context, sandbox}, so the answer comes from whatever
  // sandbox realm the context holds when Firefox runs it. During a navigation Firefox creates and
  // destroys short-lived realms (#66). If the answering realm is destroyed before the answer arrives,
  // Playwright kept a utility context whose UtilityScript handle lives in that dead realm: every
  // utility evaluate then fails, and page.title() turns the failure into "". Remember recently
  // destroyed realms so that answer is dropped; the next window realm creates the world again.
  const DESTROYED_REALMS_KEPT = 64;

  function patchBidiPageStaleUtilityWorld(BidiPage) {
    const originalOnRealmDestroyed = BidiPage.prototype._onRealmDestroyed;
    BidiPage.prototype._onRealmDestroyed = function (params) {
      const destroyed = this._playwright4jDestroyedRealms || (this._playwright4jDestroyedRealms = []);
      destroyed.push(params.realm);
      if (destroyed.length > DESTROYED_REALMS_KEPT) {
        destroyed.shift();
      }
      return originalOnRealmDestroyed.apply(this, arguments);
    };
  }

  function isRealmDestroyed(bidiPage, realm) {
    return (bidiPage._playwright4jDestroyedRealms || []).indexOf(realm) >= 0;
  }

  // Playwright's BiDi Firefox profile (third_party/firefoxPrefs.js, taken from Puppeteer) enables
  // dom.testing.testutils.enabled only for page.requestGC(). That pref exposes a TestUtils global to
  // every web page, which neither a normal Firefox nor Playwright's own Firefox build (Juggler) has.
  // Turn it off unless the caller sets it explicitly via firefoxUserPrefs.
  function patchBidiFirefoxProfilePrefs(BidiFirefox) {
    const originalPrepareUserDataDir = BidiFirefox.prototype.prepareUserDataDir;
    BidiFirefox.prototype.prepareUserDataDir = function (options, userDataDir) {
      const userPrefs = (options && options.firefoxUserPrefs) || {};
      const amended = Object.assign({}, options, {
        firefoxUserPrefs: Object.assign({ 'dom.testing.testutils.enabled': false }, userPrefs)
      });
      return originalPrepareUserDataDir.call(this, amended, userDataDir);
    };
  }

  // BidiPage.requestGC evaluates TestUtils.gc() and reports any failure as "Method not implemented.".
  // With dom.testing.testutils.enabled off by default (see patchBidiFirefoxProfilePrefs) say how to
  // turn it back on instead.
  // Read-only WebDriver BiDi access for com.aresstack.playwright4j.bidi.FirefoxBidi. Firefox accepts
  // one BiDi session, Playwright's, so a second client cannot connect. FirefoxBidi reaches this
  // object through BrowserContext.newCDPSession(page), the only page-bound session channel of the
  // Playwright protocol, and attaches with "playwright4j.bidi.attach" before anything else is
  // accepted. Only commands that run no script in the page and leave Playwright's state alone pass.
  const BIDI_READ_COMMANDS = new Set([
    'browsingContext.getTree',
    'browsingContext.locateNodes',
    'network.getData',
    'storage.getCookies'
  ]);
  const BIDI_DEFAULT_DATA_TIMEOUT_MS = 10000;

  function createBidiReadSession(SdkObject, context, target) {
    const frame = typeof target.mainFrame === 'function' ? target.mainFrame() : target;
    const session = frame._page.delegate._session;
    class BidiReadSession extends SdkObject {
      constructor() {
        super(context, 'cdp-session');
        this._attached = false;
      }

      async send(progress, method, params) {
        if (method === 'playwright4j.bidi.attach') {
          if (!this._attached) {
            this._attached = true;
            this._forwardEvents();
          }
          return { context: frame._id };
        }
        if (!this._attached) {
          throw new Error('Firefox over WebDriver BiDi has no CDP session. Use '
            + 'com.aresstack.playwright4j.bidi.FirefoxBidi.open(page) for read-only BiDi access.');
        }
        if (!BIDI_READ_COMMANDS.has(method)) {
          throw new Error('FirefoxBidi does not send ' + method + '; read-only commands: '
            + Array.from(BIDI_READ_COMMANDS).join(', '));
        }
        params = Object.assign({}, params);
        if (method !== 'network.getData') {
          return await progress.race(session.send(method, params));
        }
        // Firefox can hold network.getData for seconds (reported for 304 revalidations);
        // a caller reading many responses must not stall on one of them.
        const timeout = typeof params.timeout === 'number' ? params.timeout : BIDI_DEFAULT_DATA_TIMEOUT_MS;
        delete params.timeout;
        let timer;
        const expired = new Promise(function (resolve, reject) {
          timer = setTimeout(function () {
            reject(new Error('network.getData for request ' + params.request + ' timed out after ' + timeout + ' ms'));
          }, timeout);
        });
        try {
          return await progress.race(Promise.race([session.send(method, params), expired]));
        } finally {
          clearTimeout(timer);
        }
      }

      _forwardEvents() {
        const self = this;
        const originalEmit = session.emit;
        session.emit = function (name, params) {
          if (typeof name === 'string' && (name.indexOf('network.') === 0 || name.indexOf('browsingContext.') === 0)) {
            self.emit('event', { method: name, params: params });
          }
          return originalEmit.apply(this, arguments);
        };
        this._restore = function () {
          session.emit = originalEmit;
        };
      }

      async detach() {
        if (this._restore) {
          this._restore();
        }
        this.emit('close');
      }
    }
    return new BidiReadSession();
  }

  function patchBidiPageRequestGC(BidiPage) {
    const originalRequestGC = BidiPage.prototype.requestGC;
    if (typeof originalRequestGC !== 'function') {
      return;
    }
    BidiPage.prototype.requestGC = async function () {
      try {
        return await originalRequestGC.apply(this, arguments);
      } catch (error) {
        if (error && error.message === 'Method not implemented.') {
          throw new Error('page.requestGC() on Firefox needs firefoxUserPrefs '
            + '{"dom.testing.testutils.enabled": true}. playwright4j turns this pref off by default '
            + 'because it exposes a TestUtils global to every web page.');
        }
        throw error;
      }
    };
  }

  // playwright4j.bidi.nativeBehavior: no default viewport. Playwright emulates 1280x720 at device
  // scale factor 1 and sets screen to the same size, which replaces the window and screen the
  // browser (or Camoufox's fingerprint) reports. A caller's viewport, deviceScaleFactor or
  // isMobile is kept, and so is Playwright's validation of them.
  function nativeBehaviorContextOptions(options) {
    if (!nativeBehavior() || !options || options.viewport || options.noDefaultViewport
        || options.deviceScaleFactor !== undefined || options.isMobile !== undefined) {
      return options;
    }
    return Object.assign({}, options, { noDefaultViewport: true });
  }

  function patchBidiBrowserNativeViewport(BidiBrowser) {
    const originalNewContext = BidiBrowser.prototype.newContext;
    BidiBrowser.prototype.newContext = function (progress, options) {
      return originalNewContext.call(this, progress, isBidiFirefox(this) ? nativeBehaviorContextOptions(options) : options);
    };
  }

  function patchBidiFirefoxNativeViewport(BidiFirefox) {
    const originalInnerLaunch = BidiFirefox.prototype._innerLaunch;
    BidiFirefox.prototype._innerLaunch = function (progress, options, persistent) {
      const args = Array.prototype.slice.call(arguments);
      args[2] = nativeBehaviorContextOptions(persistent);
      return originalInnerLaunch.apply(this, args);
    };
  }

  // playwright4j.bidi.nativeBehavior: screenshots leave the text caret alone unless the caller asks
  // for caret: "hide". Hiding it writes caret-color into the style of the focused element, which
  // the page can observe.
  // The Screenshotter class is not reachable from the bundle's BiDi classes; its prototype is
  // patched once, from the first BiDi page.
  const patchedScreenshotters = new WeakSet();
  function patchBidiPageNativeCaret(BidiPage) {
    const originalInitialize = BidiPage.prototype._initialize;
    BidiPage.prototype._initialize = function () {
      const prototype = Object.getPrototypeOf(this._page.screenshotter);
      if (!patchedScreenshotters.has(prototype)) {
        patchedScreenshotters.add(prototype);
        patchScreenshotterNativeCaret(prototype);
      }
      return originalInitialize.apply(this, arguments);
    };
  }

  function patchScreenshotterNativeCaret(prototype) {
    ['screenshotPage', 'screenshotElement'].forEach(function (method) {
      const original = prototype[method];
      prototype[method] = function () {
        const args = Array.prototype.slice.call(arguments);
        const index = args.length - 1;
        const options = args[index];
        if (nativeBehavior() && options && options.caret === undefined && isBidiFirefox(this._page.browserContext._browser)) {
          args[index] = Object.assign({}, options, { caret: 'initial' });
        }
        return original.apply(this, args);
      };
    });
  }

  // playwright4j.bidi.nativeBehavior: the first preload script of a context is reported once.
  // exposeBinding/exposeFunction, addInitScript, clock, routeWebSocket and tracing snapshots install
  // scripts or globals in the page's main world that a page can see; nativeBehavior cannot keep
  // those, so it says so instead of failing or dropping the caller's script.
  const NATIVE_BEHAVIOR_PROPERTY = 'playwright4j.bidi.nativeBehavior';
  const warnedPreloadContexts = new WeakSet();
  function warnNativeBehaviorPreload(context) {
    if (!nativeBehavior() || !context || warnedPreloadContexts.has(context)) {
      return;
    }
    warnedPreloadContexts.add(context);
    console.warn('playwright4j: ' + NATIVE_BEHAVIOR_PROPERTY + ' is set, but this context now installs a '
      + 'preload script in the page\'s main world (exposeBinding/exposeFunction, addInitScript, clock, '
      + 'routeWebSocket or tracing snapshots). Pages can observe it; see docs/browser-signals.md.');
  }
  function patchBidiContextNativePreloadWarning(BidiBrowserContext) {
    const originalContextAdd = BidiBrowserContext.prototype.doAddInitScript;
    BidiBrowserContext.prototype.doAddInitScript = function () {
      warnNativeBehaviorPreload(this);
      return originalContextAdd.apply(this, arguments);
    };
  }

  function patchBidiPageNativePreloadWarning(BidiPage) {
    const originalPageAdd = BidiPage.prototype.addInitScript;
    BidiPage.prototype.addInitScript = function () {
      warnNativeBehaviorPreload(this._browserContext);
      return originalPageAdd.apply(this, arguments);
    };
  }

  // playwright4j.bidi.nativeBehavior: text from fill() and keyboard.insertText() goes through
  // Firefox's editor (execCommand "insertText" on the focused field), which fires trusted
  // beforeinput and input events, as typing or pasting does (#41). Playwright assigns the value
  // and dispatches an untrusted input event instead; that stays the way for a field the editor
  // refuses, so the result is the same either way.
  function nativeInsertText(window, text, playwrightInsertText) {
    let element = window.document.activeElement;
    while (element && element.shadowRoot)
      element = element.shadowRoot.activeElement;
    if (!element)
      return;
    const type = element.nodeName.toLowerCase();
    if (type === 'iframe' || type === 'frame')
      return element;
    if ((type === 'input' || type === 'textarea' || element.isContentEditable)
        && window.document.execCommand('insertText', false, text))
      return;
    return playwrightInsertText(window, text);
  }

  function insertTextExpression(bidiPage, bidiInsertTextSource) {
    const insert = nativeBehavior() && isBidiFirefox(bidiPage._browserContext._browser)
      ? '(' + nativeInsertText.toString() + ')(window, text, bidiInsertText)'
      : 'bidiInsertText(window, text)';
    return '(text) => {\nconst module = { exports: {} };\n' + bidiInsertTextSource + '\nreturn ' + insert + ';\n}';
  }

  // playwright4j.bidi.nativeBehavior: an action scrolls its target into view with the mouse wheel
  // (input.performActions), as a user does, instead of calling scrollIntoView from a script (#42).
  // Each round wheels the innermost scroll container that still hides the target, over the visible
  // part of that container, towards centring the target, as Playwright's scrollIntoView does.
  // A target the wheel cannot reach (a container that ignores the wheel, a transformed frame)
  // falls back to Playwright's scrollIntoView, so the action still runs.
  const NATIVE_SCROLL_ROUNDS = 8;
  const NATIVE_SCROLL_TICK_PIXELS = 120;

  function nativeScrollStep([, node], rect) {
    if (!node.isConnected)
      return 'error:notconnected';
    const element = node.nodeType === Node.ELEMENT_NODE ? node : node.parentElement;
    if (!element || element.getClientRects().length === 0)
      return 'error:notvisible';
    const window = element.ownerDocument.defaultView;
    const box = element.getBoundingClientRect();
    const target = rect
      ? { left: box.left + rect.x, top: box.top + rect.y, right: box.left + rect.x + rect.width, bottom: box.top + rect.y + rect.height }
      : { left: box.left, top: box.top, right: box.right, bottom: box.bottom };
    const root = element.ownerDocument.scrollingElement || element.ownerDocument.documentElement;
    const viewport = { left: 0, top: 0, right: root.clientWidth, bottom: root.clientHeight };
    const containers = [];
    for (let parent = element.parentElement; parent && parent !== root; parent = parent.parentElement) {
      const style = window.getComputedStyle(parent);
      const scrolls = /(auto|scroll)/.test(style.overflowX + ' ' + style.overflowY);
      if (scrolls && (parent.scrollHeight > parent.clientHeight || parent.scrollWidth > parent.clientWidth)) {
        const outer = parent.getBoundingClientRect();
        const left = outer.left + parent.clientLeft;
        const top = outer.top + parent.clientTop;
        containers.push({ scroller: parent, box: { left: left, top: top, right: left + parent.clientWidth, bottom: top + parent.clientHeight } });
      }
    }
    containers.push({ scroller: root, box: viewport });
    // How far the container has to scroll along one axis: none while the target fits inside,
    // otherwise towards centring it, within what the container can still scroll.
    function delta(start, end, boxStart, boxEnd, scrolled, scrollSize, clientSize) {
      const fits = end - start <= boxEnd - boxStart;
      if (fits ? start >= boxStart && end <= boxEnd : start <= boxStart && end >= boxEnd)
        return 0;
      const wanted = Math.round((start + end) / 2 - (boxStart + boxEnd) / 2);
      return Math.max(-scrolled, Math.min(scrollSize - clientSize - scrolled, wanted));
    }
    for (let i = 0; i < containers.length; i++) {
      const { scroller, box: area } = containers[i];
      const dx = delta(target.left, target.right, area.left, area.right, scroller.scrollLeft, scroller.scrollWidth, scroller.clientWidth);
      const dy = delta(target.top, target.bottom, area.top, area.bottom, scroller.scrollTop, scroller.scrollHeight, scroller.clientHeight);
      if (dx === 0 && dy === 0)
        continue;
      // The wheel goes to the visible part of this container; while that is empty, an outer
      // container has to scroll first.
      const visible = { left: area.left, top: area.top, right: area.right, bottom: area.bottom };
      containers.slice(i + 1).forEach(function (outer) {
        visible.left = Math.max(visible.left, outer.box.left);
        visible.top = Math.max(visible.top, outer.box.top);
        visible.right = Math.min(visible.right, outer.box.right);
        visible.bottom = Math.min(visible.bottom, outer.box.bottom);
      });
      if (visible.right - visible.left < 1 || visible.bottom - visible.top < 1)
        continue;
      return { x: (visible.left + visible.right) / 2, y: (visible.top + visible.bottom) / 2, deltaX: dx, deltaY: dy };
    }
    return 'done';
  }

  // Resolves once no scroll position under the document changed for two animation frames, so the
  // next round measures after Firefox's smooth scrolling ended.
  function nativeScrollSettled([, node]) {
    const window = node.ownerDocument.defaultView;
    const positions = () => {
      const values = [];
      for (let element = node.parentElement; element; element = element.parentElement)
        values.push(element.scrollLeft, element.scrollTop);
      return values.join();
    };
    return new Promise(resolve => {
      const deadline = Date.now() + 2000;
      let last = positions();
      let stable = 0;
      const check = () => {
        const now = positions();
        stable = now === last ? stable + 1 : 0;
        last = now;
        if (stable >= 2 || Date.now() > deadline)
          resolve();
        else
          window.requestAnimationFrame(check);
      };
      window.requestAnimationFrame(check);
    });
  }

  function wheelTicks(x, y, deltaX, deltaY) {
    const count = Math.max(1, Math.ceil(Math.max(Math.abs(deltaX), Math.abs(deltaY)) / NATIVE_SCROLL_TICK_PIXELS));
    const ticks = [];
    let sentX = 0;
    let sentY = 0;
    for (let i = 1; i <= count; i++) {
      const nextX = Math.round(deltaX * i / count);
      const nextY = Math.round(deltaY * i / count);
      ticks.push({ type: 'scroll', x: x, y: y, deltaX: nextX - sentX, deltaY: nextY - sentY });
      sentX = nextX;
      sentY = nextY;
    }
    return ticks;
  }

  // Returns Playwright's result ("done", "error:notconnected", "error:notvisible"), or null when the
  // wheel did not bring the target into view.
  async function wheelIntoView(bidiPage, handle, rect) {
    let offset = { x: 0, y: 0 };
    if (handle._frame !== bidiPage._page.mainFrame()) {
      const frameElement = await handle._frame.frameElement(nullProgress);
      try {
        const result = await wheelIntoView(bidiPage, frameElement);
        if (result !== 'done')
          return result === 'error:notconnected' ? result : null;
      } finally {
        frameElement.dispose();
      }
      offset = await bidiPage._framePosition(handle._frame);
      if (!offset)
        return null;
    }
    for (let round = 0; round < NATIVE_SCROLL_ROUNDS; round++) {
      const step = await handle.evaluateInUtility(nativeScrollStep, rect || null);
      if (typeof step === 'string')
        return step;
      await bidiPage._session.send('input.performActions', {
        context: bidiPage._session.sessionId,
        actions: [{
          type: 'wheel',
          id: 'pw_mouse_wheel',
          actions: wheelTicks(Math.floor(offset.x + step.x), Math.floor(offset.y + step.y), step.deltaX, step.deltaY)
        }]
      });
      await handle.evaluateInUtility(nativeScrollSettled, null);
    }
    return null;
  }

  // The bundle's nullProgress, for frame.frameElement() outside of an action's progress.
  let nullProgress;
  function patchBidiPageNativeScroll(BidiPage, bundleNullProgress) {
    nullProgress = bundleNullProgress;
    const originalScroll = BidiPage.prototype.scrollRectIntoViewIfNeeded;
    BidiPage.prototype.scrollRectIntoViewIfNeeded = async function (handle, rect) {
      if (!nativeBehavior() || !isBidiFirefox(this._browserContext._browser)) {
        return originalScroll.apply(this, arguments);
      }
      const result = await wheelIntoView(this, handle, rect).catch(function (error) {
        if (error && /Node is detached|not connected/.test(String(error.message)))
          return 'error:notconnected';
        throw error;
      });
      if (result !== null) {
        return result;
      }
      pw4jDebug('nativeBehavior: the mouse wheel did not bring the element into view; using scrollIntoView');
      return originalScroll.apply(this, arguments);
    };
  }

  // Firefox tabs and windows over WebDriver BiDi. Firefox already shows a window with one tab when
  // its BiDi endpoint starts listening, and reports that tab (browsingContext.contextCreated) while
  // Playwright subscribes to events. BidiBrowser.connect subscribes before it creates the default
  // context of a persistent launch, so the event is dropped, and connect then opens a second window
  // for the first page (browsingContext.create type "window"); every newPage() opens yet another
  // window. For Firefox:
  //  - the persistent default context takes over the top-level tabs Firefox started with as its
  //    pages, together with their realms and load state (whose events were dropped as well);
  //  - newPage() opens a tab next to a page of the same browser context; only the first page of a
  //    context without pages gets a window of its own (a persistent context opens a tab);
  //  - a window keeps at least one tab, but a context may have no pages, as in Playwright. Firefox
  //    ignores browsingContext.close for its very last tab (success, but no contextDestroyed, so
  //    page.close() would wait forever), so a blank tab is opened next to it first. In a persistent
  //    profile (browser.tabs.closeWindowWithLastTab=false, see patchBidiFirefoxUserJs) Firefox
  //    itself replaces the last tab of a window, closed in its UI or over BiDi, with a blank tab
  //    (contextDestroyed, then contextCreated in the same window). These blank tabs are hidden:
  //    not reported as pages, and the next newPage() of the persistent context takes one over (the
  //    one opened before closing the last page) or opens a tab in place of Firefox's replacement.
  //    A hidden tab the user navigates somewhere becomes a page.
  function isBidiFirefox(browser) {
    return !!browser && !!browser.options && browser.options.name === 'firefox';
  }

  // How long after the last tab of a window closed Firefox's replacement tab is expected.
  const FIREFOX_REPLACEMENT_TAB_MILLIS = 5000;
  // What a hidden tab shows until the user navigates it.
  const FIREFOX_BLANK_TAB_URLS = ['about:blank', 'about:newtab', 'about:home'];

  // Playwright's own BidiBrowser._onBrowsingContextCreated: reports a top-level tab as a page.
  let reportFirefoxTabAsPage;

  // Per browser: the window of every top-level tab (id -> clientWindow), the hidden blank tabs
  // (id -> browsingContext.Info), the hidden tabs keepLastFirefoxTab opened (ids), the windows
  // whose last tab just closed (clientWindow -> deadline for Firefox's replacement tab) and, while
  // keepLastFirefoxTab opens one, the tabs being opened.
  function firefoxTabs(browser) {
    if (browser.__playwright4jFirefoxTabs) {
      return browser.__playwright4jFirefoxTabs;
    }
    const tabs = { windows: new Map(), hidden: new Map(), kept: new Set(), replacing: new Map(), opening: undefined };
    browser.__playwright4jFirefoxTabs = tabs;
    // Events of tabs without a page (hidden tabs, or tabs of no browser context) reach only the
    // browser session.
    const session = browser._browserSession;
    session.on('browsingContext.contextDestroyed', function (event) {
      firefoxTabClosed(browser, event);
    });
    session.on('browsingContext.navigationStarted', function (event) {
      const info = tabs.hidden.get(event.context);
      if (!info || FIREFOX_BLANK_TAB_URLS.indexOf(event.url) >= 0) {
        return;
      }
      const bidiPage = revealFirefoxTab(browser, info);
      if (bidiPage) {
        bidiPage._session.dispatchMessage({ type: 'event', method: 'browsingContext.navigationStarted', params: event });
      }
    });
    // Playwright intercepts HTTP authentication, and every request while a route is set, for all
    // tabs, but answers only for pages: let Firefox go on with the requests of the other tabs.
    session.on('network.authRequired', function (event) {
      if (event.isBlocked) {
        session.sendMayFail('network.continueWithAuth', { request: event.request.request, action: 'default' });
      }
    });
    session.on('network.beforeRequestSent', function (event) {
      if (event.isBlocked) {
        session.sendMayFail('network.continueRequest', { request: event.request.request });
      }
    });
    return tabs;
  }

  // Reports a hidden or untracked top-level tab as a page (of the persistent default context, for
  // Firefox's default user context); returns its BidiPage, if any.
  function revealFirefoxTab(browser, info) {
    const tabs = firefoxTabs(browser);
    tabs.hidden.delete(info.context);
    tabs.kept.delete(info.context);
    tabs.windows.set(info.context, info.clientWindow);
    reportFirefoxTabAsPage.call(browser, Object.assign({}, info, { parent: null }));
    return browser._bidiPages.get(info.context);
  }

  function firefoxTabClosed(browser, event) {
    if (event.parent) {
      return;
    }
    const tabs = firefoxTabs(browser);
    tabs.windows.delete(event.context);
    tabs.hidden.delete(event.context);
    tabs.kept.delete(event.context);
    if (!browser._defaultContext || event.userContext !== 'default' || !event.clientWindow) {
      return;
    }
    for (const clientWindow of tabs.windows.values()) {
      if (clientWindow === event.clientWindow) {
        return;
      }
    }
    // The last tab of its window: Firefox opens a blank tab in its place next (popup windows close
    // instead, and the deadline passes).
    tabs.replacing.set(event.clientWindow, Date.now() + FIREFOX_REPLACEMENT_TAB_MILLIS);
  }

  function patchBidiBrowserFirefoxTabs(BidiBrowser, BidiBrowserContext) {
    const originalOnBrowsingContextCreated = BidiBrowser.prototype._onBrowsingContextCreated;
    reportFirefoxTabAsPage = originalOnBrowsingContextCreated;
    BidiBrowser.prototype._onBrowsingContextCreated = function (event) {
      if (!isBidiFirefox(this) || event.parent) {
        return originalOnBrowsingContextCreated.apply(this, arguments);
      }
      const tabs = firefoxTabs(this);
      tabs.windows.set(event.context, event.clientWindow);
      if (event.userContext === 'default' && !event.originalOpener) {
        // The blank tab opened before closing the very last page; its event arrives before the
        // create response tells its id (see keepLastFirefoxTab).
        if (tabs.opening) {
          tabs.opening.set(event.context, event);
          return;
        }
        const replacementUntil = tabs.replacing.get(event.clientWindow);
        if (replacementUntil !== undefined) {
          tabs.replacing.delete(event.clientWindow);
          if (Date.now() < replacementUntil) {
            tabs.hidden.set(event.context, event);
            return;
          }
        }
      }
      return originalOnBrowsingContextCreated.apply(this, arguments);
    };

    // contextDestroyed of a page arrives on its page session, which hands it to the browser here.
    const originalOnBrowsingContextDestroyed = BidiBrowser.prototype._onBrowsingContextDestroyed;
    BidiBrowser.prototype._onBrowsingContextDestroyed = function (event) {
      const result = originalOnBrowsingContextDestroyed.apply(this, arguments);
      if (isBidiFirefox(this)) {
        firefoxTabClosed(this, event);
      }
      return result;
    };

    const originalDoCreateNewPage = BidiBrowserContext.prototype.doCreateNewPage;
    BidiBrowserContext.prototype.doCreateNewPage = async function () {
      const browser = this._browser;
      if (!isBidiFirefox(browser)) {
        return originalDoCreateNewPage.apply(this, arguments);
      }
      if (this === browser._defaultContext) {
        // The first call comes from BidiBrowser.connect for a persistent launch: take over the
        // tabs Firefox started with. Later calls take over a hidden blank tab, if there is one.
        const startup = !this.__playwright4jStartTabsTaken;
        this.__playwright4jStartTabsTaken = true;
        const taken = startup ? await takeOverFirefoxStartTabs(this) : await takeOverHiddenFirefoxTab(this);
        if (taken) {
          return taken._page;
        }
      }
      const reference = this._bidiPages().find(function (bidiPage) {
        return !bidiPage._opener;
      });
      const { context } = await browser._browserSession.send('browsingContext.create', {
        type: reference || this === browser._defaultContext ? 'tab' : 'window',
        referenceContext: reference ? reference._session.sessionId : undefined,
        userContext: this._browserContextId
      });
      // A tab opened just when Firefox replaced the last tab of its window may pass for that
      // replacement.
      const hidden = firefoxTabs(browser).hidden.get(context);
      const bidiPage = browser._bidiPages.get(context) || (hidden && revealFirefoxTab(browser, hidden));
      return bidiPage._page;
    };
  }

  // Reports the top-level tabs Firefox started with (its default user context) as pages of the
  // persistent default context; returns the first.
  async function takeOverFirefoxStartTabs(browserContext) {
    const browser = browserContext._browser;
    const { contexts } = await browser._browserSession.send('browsingContext.getTree', { maxDepth: 0 });
    const taken = [];
    for (const info of contexts) {
      if (!info.parent && info.userContext === 'default' && !browser._bidiPages.has(info.context)) {
        const bidiPage = revealFirefoxTab(browser, info);
        if (bidiPage) {
          taken.push(bidiPage);
        }
      }
    }
    await Promise.all(taken.map(restoreTakenOverPage));
    return taken[0];
  }

  // Reports a hidden blank tab as a new page of the persistent default context, if there is one.
  async function takeOverHiddenFirefoxTab(browserContext) {
    const browser = browserContext._browser;
    const tabs = firefoxTabs(browser);
    if (!tabs.hidden.size) {
      return undefined;
    }
    const { contexts } = await browser._browserSession.send('browsingContext.getTree', { maxDepth: 0 });
    const hidden = contexts.filter(function (info) { return tabs.hidden.has(info.context); });
    tabs.hidden.forEach(function (info, id) {
      if (!hidden.some(function (open) { return open.context === id; })) {
        tabs.hidden.delete(id);
        tabs.kept.delete(id);
      }
    });
    const blank = hidden.filter(function (info) { return FIREFOX_BLANK_TAB_URLS.indexOf(info.url) >= 0; });
    // A hidden tab the user has navigated is a page (in case its navigationStarted was missed).
    for (const info of hidden) {
      if (blank.indexOf(info) < 0) {
        const bidiPage = revealFirefoxTab(browser, info);
        if (bidiPage) {
          await restoreTakenOverPage(bidiPage);
        }
      }
    }
    // The blank tab opened before closing the very last page (see keepLastFirefoxTab) is taken over
    // as it is.
    const untouched = blank.find(function (info) { return info.url === 'about:blank' && tabs.kept.has(info.context); });
    if (untouched) {
      const bidiPage = revealFirefoxTab(browser, untouched);
      if (bidiPage) {
        await restoreTakenOverPage(bidiPage);
        // The tab may be in the background (see keepLastFirefoxTab); select it, as
        // browsingContext.create does with a new tab.
        await browser._browserSession.send('browsingContext.activate', { context: untouched.context }).catch(function (error) {
          traceWebSocket('firefox-activate-hidden-tab-failed:' + (error && error.message));
        });
      }
      return bidiPage;
    }
    if (!blank.length) {
      return undefined;
    }
    // Any other is Firefox's own replacement tab. It loads about:newtab, a privileged page that may
    // not navigate to every URL (data: is refused), and shows its initial about:blank until that
    // page commits, which can take seconds while Firefox starts the process for it: open a fresh
    // tab in its place, whatever it shows now.
    const replaced = blank[0].context;
    tabs.hidden.delete(replaced);
    tabs.kept.delete(replaced);
    const { context } = await browser._browserSession.send('browsingContext.create', {
      type: 'tab',
      referenceContext: replaced,
      userContext: 'default'
    });
    await browser._browserSession.send('browsingContext.close', { context: replaced }).catch(function (error) {
      traceWebSocket('firefox-close-replaced-tab-failed:' + (error && error.message));
    });
    return browser._bidiPages.get(context);
  }

  // The realms of a taken-over tab and its load events came before it had a page session. Report
  // its window realms (the utility world follows on demand) and the lifecycle events its document
  // has already passed.
  // Playwright's utility world; the shim reads page state there, never in the main world.
  const UTILITY_WORLD_NAME = '__playwright_utility_world__';

  async function restoreTakenOverPage(bidiPage) {
    const contextId = bidiPage._session.sessionId;
    try {
      const { realms } = await bidiPage._session.send('script.getRealms', { context: contextId, type: 'window' });
      for (const realm of realms) {
        bidiPage._onRealmCreated(Object.assign({ context: contextId }, realm));
      }
      const main = realms.find(function (realm) { return !realm.sandbox; });
      if (!main) {
        return;
      }
      const evaluation = await bidiPage._session.send('script.evaluate', {
        expression: 'document.readyState',
        target: { context: contextId, sandbox: UTILITY_WORLD_NAME },
        awaitPromise: false
      });
      const readyState = evaluation && evaluation.type === 'success' && evaluation.result ? evaluation.result.value : undefined;
      const frame = bidiPage._page.mainFrame();
      if (readyState === 'interactive' || readyState === 'complete') {
        bidiPage._page.frameManager.frameLifecycleEvent(frame._id, 'domcontentloaded');
      }
      if (readyState === 'complete') {
        bidiPage._page.frameManager.frameLifecycleEvent(frame._id, 'load');
      }
    } catch (error) {
      // The tab closed meanwhile; its page reports that through contextDestroyed.
      traceWebSocket('firefox-tab-takeover-failed:' + (error && error.message));
    }
  }

  // Before the page of Firefox's very last tab is closed, open a blank tab in its window (in the
  // background, so a beforeunload prompt stays on the closing page) and hide it. It is opened in
  // Firefox's default user context, so that removing the browser context of the closing page
  // (launch() with newContext()) does not close it as well.
  async function keepLastFirefoxTab(bidiPage) {
    const browser = bidiPage._browserContext._browser;
    const contextId = bidiPage._session.sessionId;
    const { contexts } = await browser._browserSession.send('browsingContext.getTree', { maxDepth: 0 });
    if (contexts.length !== 1 || contexts[0].context !== contextId) {
      return;
    }
    const tabs = firefoxTabs(browser);
    const opening = new Map();
    tabs.opening = opening;
    let keptTab;
    try {
      keptTab = (await browser._browserSession.send('browsingContext.create', {
        type: 'tab',
        referenceContext: contextId,
        userContext: 'default',
        background: true
      })).context;
      const clientWindow = contexts[0].clientWindow;
      tabs.windows.set(keptTab, clientWindow);
      tabs.hidden.set(keptTab, opening.get(keptTab) || {
        context: keptTab, url: 'about:blank', userContext: 'default', clientWindow: clientWindow,
        originalOpener: null, parent: null, children: null
      });
      tabs.kept.add(keptTab);
    } finally {
      tabs.opening = undefined;
      // Another tab opened at the same moment (by the user or a page) is a regular page.
      opening.forEach(function (event, id) {
        if (id !== keptTab) {
          browser._onBrowsingContextCreated(event);
        }
      });
    }
  }

  function patchBidiPageFirefoxLastTab(BidiPage) {
    const originalClosePage = BidiPage.prototype.closePage;
    BidiPage.prototype.closePage = async function () {
      if (isBidiFirefox(this._browserContext._browser)) {
        try {
          await keepLastFirefoxTab(this);
        } catch (error) {
          // The browser is going away; the close below reports what happened.
          traceWebSocket('firefox-keep-last-tab-failed:' + (error && error.message));
        }
      }
      return originalClosePage.apply(this, arguments);
    };
  }

  // Playwright's createProfile (third_party/firefoxPrefs.js) rewrites user.js with its own
  // preferences on every launch and drops whatever the file held. The lines of an existing user.js
  // are kept in a marked block before Playwright's lines: Firefox applies the last value it reads,
  // so for the same preference Playwright's (or firefoxUserPrefs) wins on this launch, and the
  // profile's own value applies again on a launch that no longer sets it. On the next launch only
  // the marked block counts as the profile's own; the rest is Playwright's from last time. The file
  // is handled as bytes, whatever its encoding.
  // Defaults added to Playwright's list, unless firefoxUserPrefs sets them: no first-run pages it
  // misses (about:welcome onboarding, privacy notice tab), and, for a persistent launch, closing the
  // last tab of a window replaces it with a blank tab instead of closing the window, which for the
  // last window would end Firefox and with it the persistent context (see
  // patchBidiBrowserFirefoxTabs). Not for launch(): there removing a context closes the tabs of its
  // window, and the window must go with them. Popup windows close with their last tab either way.
  const FIREFOX_DEFAULT_PREFS = {
    'browser.aboutwelcome.enabled': false,
    'toolkit.telemetry.reportingpolicy.firstRun': false
  };
  const FIREFOX_PERSISTENT_DEFAULT_PREFS = {
    'browser.tabs.closeWindowWithLastTab': false
  };
  const PERSISTENT_LAUNCH_OPTION = '__playwright4jPersistentLaunch';
  const KEPT_USER_JS_BEGIN = '// playwright4j: the lines below are kept from this profile\'s own user.js on every launch.';
  const KEPT_USER_JS_END = '// playwright4j: end of the kept lines; Playwright\'s preferences follow.';

  // The preference a line sets when the line is exactly as Playwright writes it,
  // user_pref(<JSON name>, <JSON value>); undefined for any other line.
  function playwrightUserJsPrefName(line) {
    const match = /^user_pref\((".*?"), (.*)\);$/.exec(line);
    if (!match) {
      return undefined;
    }
    try {
      const name = JSON.parse(match[1]);
      return line === 'user_pref(' + JSON.stringify(name) + ', ' + JSON.stringify(JSON.parse(match[2])) + ');' ? name : undefined;
    } catch (notJson) {
      return undefined;
    }
  }

  function readUserJsBytes(path) {
    // latin1 maps each byte to one character and back.
    return modules.fs.existsSync(path) ? String(modules.fs.readFileSync(path, 'latin1')).replace(/^\xEF\xBB\xBF/, '') : '';
  }

  // The profile's own lines of the user.js found at launch: the marked block, or every line of a
  // user.js without the markers. Not the lines of one that Playwright wrote, as plain Playwright and
  // playwright4j up to 0.2.1 did on every launch: it holds Playwright's default preferences, and
  // keeping its lines would keep that launch's firefoxUserPrefs for good. A user.js counts as
  // Playwright's when every line is in Playwright's own format (no comment, nothing else) and it
  // sets at least 90% of the default preferences written now (a Playwright update may add or drop
  // a few).
  function keptUserJsLines(previous, playwrightDefaults) {
    const lines = previous.split(/\r?\n/);
    const begin = lines.indexOf(KEPT_USER_JS_BEGIN);
    const end = lines.indexOf(KEPT_USER_JS_END);
    if (begin < 0 || end < begin) {
      const names = lines.filter(function (line) { return line.trim(); }).map(playwrightUserJsPrefName);
      const defaultsFound = new Set(names.filter(function (name) { return playwrightDefaults.has(name); }));
      if (names.indexOf(undefined) < 0 && playwrightDefaults.size >= 20 && defaultsFound.size >= playwrightDefaults.size * 0.9) {
        return [];
      }
    }
    const own = begin >= 0 && end > begin ? lines.slice(begin + 1, end) : lines;
    return own.filter(function (line) {
      return line.trim() && line !== KEPT_USER_JS_BEGIN && line !== KEPT_USER_JS_END;
    });
  }

  // Firefox reads user.js as one text: an unclosed /* comment or string, or a last statement
  // without its ';', at the end of the kept lines would take Playwright's first lines with it
  // (after an error Firefox skips to the next ';'). Firefox only reports such a tail at the end of
  // a file, so the kept lines stay as they are, and the guard closes them off after the END
  // marker: it ends the comment or string, and an unfinished statement fails on a stray keyword
  // and ends at the ';' after it, so it is not applied, as at the end of the profile's own file.
  // A NUL byte outside a comment ends what Firefox reads (a UTF-16 user.js has one after its
  // first character): stop is its index, or -1.
  function scanUserJs(text) {
    let state = 'code';
    let quote = '';
    let pending = false;
    for (let i = 0; i < text.length; i++) {
      const c = text[i];
      const next = text[i + 1];
      if (c === '\0') {
        if (state === 'code' || state === 'string') {
          return { stop: i, guard: '' };
        }
        // It ends a comment; after one in a /* comment Firefox skips to the next ';'.
        pending = pending || state === 'block';
        state = 'code';
      } else if (state === 'line') {
        if (c === '\n' || c === '\r') {
          state = 'code';
        }
      } else if (state === 'block') {
        if (c === '*' && next === '/') {
          state = 'code';
          i++;
        }
      } else if (state === 'string') {
        if (c === '\\') {
          i++;
        } else if (c === quote) {
          state = 'code';
        }
      } else if (c === '#' || (c === '/' && next === '/')) {
        state = 'line';
      } else if (c === '/' && next === '*') {
        state = 'block';
        i++;
      } else if (c === '/') {
        // Not a comment: Firefox reports it and takes the next character with it.
        pending = true;
        i++;
      } else if (c === '"' || c === "'") {
        state = 'string';
        quote = c;
        pending = true;
      } else if (c === ';') {
        pending = false;
      } else if (!/\s/.test(c)) {
        pending = true;
      }
    }
    return {
      stop: -1,
      guard: (state === 'block' ? '*/\n' : state === 'string' ? quote + '\n' : '') + (pending ? 'user_pref;\n' : '')
    };
  }

  // playwright4j.bidi.nativeBehavior: Playwright's test preferences that change what a page or
  // server can observe (popup blocker, background timers and rendering, focus, tracking and cookie
  // protection, HTTPS-First, speculative connections, offline detection, test geolocation). Their
  // lines are left out of user.js, so the browser's own default applies: Firefox's, or for
  // Camoufox the value camoufox.cfg sets with defaultPref. firefoxUserPrefs still sets any of them.
  const NATIVE_BEHAVIOR_PREFS = [
    'browser.contentblocking.features.standard',
    'dom.disable_open_during_load',
    'dom.min_background_timeout_value',
    'dom.min_background_timeout_value_without_budget_throttling',
    'dom.security.https_first',
    'dom.timeout.enable_budget_timer_throttling',
    'focusmanager.testmode',
    'geo.provider.testing',
    'layout.testing.top-level-always-active',
    'network.http.speculative-parallel-limit',
    'network.manage-offline-status'
  ];

  // Firefox's Remote Agent sets its own recommended test preferences at startup, the ones above
  // among them, for every preference without a user value (remote/shared/RecommendedPreferences).
  // Leaving Playwright's lines out would hand them to the Remote Agent, so it is told not to.
  // Without them Firefox's 3 s successive-dialog limit applies: the next dialog within it gets a
  // "prevent more dialogs" checkbox, and Camoufox (tab-modal prompts) never reports that dialog over
  // BiDi, so the page and the caller's call hang. 0 is what the Remote Agent sets.
  const NATIVE_BEHAVIOR_ADDED_PREFS = {
    'remote.prefs.recommended': false,
    'dom.successive_dialog_time_limit': 0
  };

  function nativeBehavior() {
    return host.nativeBehavior() === true;
  }

  function nativeBehaviorDroppedPref(name, callerPrefs) {
    return NATIVE_BEHAVIOR_PREFS.indexOf(name) >= 0 && !Object.prototype.hasOwnProperty.call(callerPrefs, name);
  }

  function nativeBehaviorUserJs(written, callerPrefs) {
    if (!nativeBehavior()) {
      return written;
    }
    return written.split(/\r?\n/).filter(function (line) {
      return !nativeBehaviorDroppedPref(playwrightUserJsPrefName(line), callerPrefs);
    }).join('\n');
  }

  // Firefox stores user.js values in prefs.js, so a persistent profile once launched without the
  // flag keeps Playwright's values there. A prefs.js line is removed only when it is exactly a line
  // Playwright wrote after the END marker of the previous user.js: user.js applied that value on
  // every launch, so it is Playwright's. Any other value, a profile without the marker (playwright4j
  // up to 0.2.1) included, may be the user's and stays. Before the first removal the file is copied
  // to prefs.js.playwright4j-backup, so a value removed by mistake can be restored.
  const NATIVE_BEHAVIOR_PREFS_JS_BACKUP_SUFFIX = '.playwright4j-backup';
  function nativeBehaviorPrefsJs(prefsJs, previousUserJs, callerPrefs) {
    const end = previousUserJs.indexOf(KEPT_USER_JS_END);
    if (!nativeBehavior() || end < 0 || !modules.fs.existsSync(prefsJs)) {
      return;
    }
    const written = new Set(previousUserJs.slice(end).split(/\r?\n/));
    const lines = String(modules.fs.readFileSync(prefsJs, 'latin1')).split('\n');
    const kept = lines.filter(function (line) {
      const text = line.replace(/\r$/, '');
      return !(written.has(text) && nativeBehaviorDroppedPref(playwrightUserJsPrefName(text), callerPrefs));
    });
    if (kept.length !== lines.length) {
      // The first cleanup keeps the original file; later ones would only copy cleaned files.
      const backup = prefsJs + NATIVE_BEHAVIOR_PREFS_JS_BACKUP_SUFFIX;
      if (!modules.fs.existsSync(backup)) {
        modules.fs.copyFileSync(prefsJs, backup);
      }
      modules.fs.writeFileSync(prefsJs, global.Buffer.from(kept.join('\n'), 'latin1'));
    }
  }

  // Playwright's BiDi browser answers a context.route() with network.setCacheBehavior "bypass"
  // for the whole browser (BidiBrowser.updateCacheBehavior, no contexts filter), so every other
  // context and tab skips the HTTP cache too (#49). Send it per top-level browsing context
  // instead, for the pages whose own or whose context's routes need it.
  const pageCacheBehavior = new WeakMap();
  function syncBidiPageCacheBehavior(bidiPage) {
    const networkManager = bidiPage._networkManager;
    const wanted = bidiPage._page.needsRequestInterception() || !!(networkManager && networkManager._credentials)
      ? 'bypass' : 'default';
    if (pageCacheBehavior.get(bidiPage) === wanted || (!pageCacheBehavior.has(bidiPage) && wanted === 'default')) {
      return Promise.resolve();
    }
    pageCacheBehavior.set(bidiPage, wanted);
    return bidiPage._session.sendMayFail('network.setCacheBehavior',
        { cacheBehavior: wanted, contexts: [bidiPage._session.sessionId] });
  }
  function patchBidiBrowserCacheBehavior(BidiBrowser) {
    BidiBrowser.prototype.updateCacheBehavior = async function () {
      await Promise.all([...this._bidiPages.values()].map(syncBidiPageCacheBehavior));
    };
    const originalOnBrowsingContextCreated = BidiBrowser.prototype._onBrowsingContextCreated;
    BidiBrowser.prototype._onBrowsingContextCreated = function (event) {
      const result = originalOnBrowsingContextCreated.apply(this, arguments);
      const bidiPage = !event.parent && this._bidiPages.get(event.context);
      if (bidiPage) {
        syncBidiPageCacheBehavior(bidiPage);
      }
      return result;
    };
  }
  // BidiNetworkManager sets the page's cache behaviour from the page's own routes only; it would
  // reset a page whose context still routes.
  function patchBidiPageCacheBehavior(BidiPage) {
    const originalUpdateRequestInterception = BidiPage.prototype.updateRequestInterception;
    BidiPage.prototype.updateRequestInterception = async function () {
      await originalUpdateRequestInterception.apply(this, arguments);
      pageCacheBehavior.delete(this);
      await syncBidiPageCacheBehavior(this);
    };
  }

  function patchBidiFirefoxUserJs(BidiFirefox) {
    // BrowserType._prepareToLaunch knows whether the launch is persistent, prepareUserDataDir does
    // not: pass it along on a copy of the launch options.
    const originalPrepareToLaunch = BidiFirefox.prototype._prepareToLaunch;
    BidiFirefox.prototype._prepareToLaunch = function (options, isPersistent, userDataDir) {
      const marked = Object.assign({}, options);
      marked[PERSISTENT_LAUNCH_OPTION] = !!isPersistent;
      return originalPrepareToLaunch.call(this, marked, isPersistent, userDataDir);
    };
    const originalPrepareUserDataDir = BidiFirefox.prototype.prepareUserDataDir;
    BidiFirefox.prototype.prepareUserDataDir = async function (options, userDataDir) {
      const userJs = modules.path.join(String(userDataDir), 'user.js');
      const previous = readUserJsBytes(userJs);
      const prefs = Object.assign({}, FIREFOX_DEFAULT_PREFS,
          options && options[PERSISTENT_LAUNCH_OPTION] ? FIREFOX_PERSISTENT_DEFAULT_PREFS : {},
          nativeBehavior() ? NATIVE_BEHAVIOR_ADDED_PREFS : {},
          (options && options.firefoxUserPrefs) || {});
      await originalPrepareUserDataDir.call(this, Object.assign({}, options, { firefoxUserPrefs: prefs }), userDataDir);
      const callerPrefs = (options && options.firefoxUserPrefs) || {};
      const written = nativeBehaviorUserJs(readUserJsBytes(userJs), callerPrefs);
      nativeBehaviorPrefsJs(modules.path.join(String(userDataDir), 'prefs.js'), previous, callerPrefs);
      const playwrightDefaults = new Set(written.split(/\r?\n/).map(playwrightUserJsPrefName).filter(function (name) {
        return name !== undefined && !Object.prototype.hasOwnProperty.call(prefs, name);
      }));
      let kept = previous ? keptUserJsLines(previous, playwrightDefaults).map(function (line) { return line + '\n'; }).join('') : '';
      // Nothing after a NUL byte applies from the profile's own file either; before Playwright's
      // lines it would hide them, so the kept lines end there.
      const stop = scanUserJs(kept).stop;
      if (stop >= 0) {
        kept = kept.slice(0, stop).replace(/([^\n])$/, '$1\n');
      }
      // The markers are written even when nothing is kept: without them the next launch would take
      // the lines Playwright wrote now, firefoxUserPrefs included, for the profile's own.
      // The END marker counts for the guard: an open string ends at its apostrophe.
      const block = KEPT_USER_JS_BEGIN + '\n' + kept + KEPT_USER_JS_END + '\n';
      const text = block + scanUserJs(block).guard + written + '\n';
      modules.fs.writeFileSync(userJs, global.Buffer.from(text, 'latin1'));
    };
  }

  // On every new document Playwright's BiDi page evaluates "1 + 1" in its utility world only to
  // create that world, and sends it with userActivation: true. Firefox then marks the page as
  // activated by the user (navigator.userActivation.hasBeenActive/isActive) before any input
  // event, which neither a normal Firefox, Playwright's Juggler Firefox nor Chrome over CDP does.
  // The same holds for every other utility-world evaluation: Playwright reads the page there
  // (page.content(), textContent(), count(), screenshots), and a read must not
  // activate the page (#37). Only the caller's main-world evaluations keep upstream behaviour;
  // with playwright4j.bidi.nativeBehavior they run without activation too, as a page's own script
  // does, so main-world reads (allTextContents(), boundingBox()) do not activate the page either.
  // Evaluations Playwright sends for its own setup, never for the caller: the utility-world touch,
  // creating its InjectedScript/UtilityScript in a world, and injectedScript.extend (the recorder).
  const PLAYWRIGHT_SETUP_SCRIPT = /new \(module\.exports\.(?:InjectedScript|UtilityScript)\(\)\)\(/;
  function isEvaluationWithoutActivation(method, params, utilityWorldName) {
    if (params.target && params.target.sandbox === utilityWorldName) {
      return true;
    }
    if (method === 'script.evaluate') {
      const expression = String(params.expression || '');
      return PLAYWRIGHT_SETUP_SCRIPT.test(expression);
    }
    if (method === 'script.callFunction' && Array.isArray(params.arguments)) {
      return params.arguments.some(function (argument) {
        return argument && argument.type === 'string' && /^\(injectedScript, \{ source: \w+, arg: \w+ \}\) => \{\s*injectedScript\.extend\(\w+, \w+\);\s*\}$/.test(String(argument.value));
      });
    }
    return false;
  }

  // Since Playwright 1.62 the evaluation sits in _createUtilityWorld, which also uses its result:
  // that one keeps its code, and its session sends the touch without activation.
  const utilityWorldTouchSessions = new WeakSet();
  function patchBidiPageTouchUtilityWorld(BidiPage, utilityWorldName) {
    if (typeof BidiPage.prototype._createUtilityWorld === 'function') {
      const originalCreateUtilityWorld = BidiPage.prototype._createUtilityWorld;
      BidiPage.prototype._createUtilityWorld = function () {
        const session = this._session;
        if (session && !utilityWorldTouchSessions.has(session)) {
          utilityWorldTouchSessions.add(session);
          const originalSend = session.send;
          session.send = function (method, params) {
            if (params && params.userActivation === true
                && (nativeBehavior() || isEvaluationWithoutActivation(method, params, utilityWorldName))) {
              arguments[1] = Object.assign({}, params, { userActivation: false });
            }
            return originalSend.apply(this, arguments);
          };
        }
        return originalCreateUtilityWorld.apply(this, arguments);
      };
      return;
    }
    if (typeof BidiPage.prototype._touchUtilityWorld !== 'function') {
      return;
    }
    BidiPage.prototype._touchUtilityWorld = async function (context) {
      await this._session.sendMayFail('script.evaluate', {
        expression: '1 + 1',
        target: { context: context, sandbox: utilityWorldName },
        serializationOptions: { maxObjectDepth: 10, maxDomDepth: 10 },
        awaitPromise: true,
        userActivation: false
      });
    };
  }

  // Before a screenshot Playwright appends and removes a <style> in every frame to restart CSS
  // animations in step, a DOM change the page can observe (#40). Firefox keeps animations stable
  // without it, as Playwright's Juggler Firefox, which does not toggle either.
  function patchBidiPageNoScreenshotStyleToggle(BidiPage) {
    BidiPage.prototype.shouldToggleStyleSheetToSyncAnimations = function () {
      return false;
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

    if (resourceName.endsWith('/lib/server/bidi/bidiFirefox.js') && exportsObject.BidiFirefox) {
      patchBidiFirefoxProfilePrefs(exportsObject.BidiFirefox);
      patchBidiFirefoxUserJs(exportsObject.BidiFirefox);
    }

    if (resourceName.endsWith('/lib/server/bidi/bidiPage.js') && exportsObject.BidiPage) {
      patchBidiPageInitialBlankLoad(exportsObject.BidiPage);
      patchBidiPageRequestGC(exportsObject.BidiPage);
      patchBidiPageTouchUtilityWorld(exportsObject.BidiPage, UTILITY_WORLD_NAME);
      patchBidiPageFirefoxLastTab(exportsObject.BidiPage);
    }

    if (resourceName.endsWith('/lib/server/bidi/bidiBrowser.js') && exportsObject.BidiBrowser && exportsObject.BidiBrowserContext) {
      patchBidiBrowserFirefoxTabs(exportsObject.BidiBrowser, exportsObject.BidiBrowserContext);
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

  function replaceCoreBundleAnchor(source, anchor, replacement, expectedCount) {
    const count = source.split(anchor).length - 1;
    if (count !== expectedCount) {
      throw new Error('Unsupported Playwright Core bundle: expected ' + expectedCount + ' x "' + anchor + '", found ' + count);
    }
    return source.split(anchor).join(replacement);
  }

  function patchCoreBundleSource(source) {
    const HostBackedPipeTransport = createHostBackedPipeTransport();
    const HostBackedWebSocketTransport = createHostBackedWebSocketTransport();
    const patched = new WeakSet();
    const bidiPageClasses = [];
    function patchOnce(target, apply) {
      if (!patched.has(target)) {
        patched.add(target);
        apply();
      }
      return target;
    }
    global.__playwright4jCoreBundle = {
      PipeTransport: HostBackedPipeTransport,
      WebSocketTransport: HostBackedWebSocketTransport,
      bidiFirefox: function (BidiFirefox) {
        return patchOnce(BidiFirefox, function () {
          patchBidiFirefoxProfilePrefs(BidiFirefox);
          patchBidiFirefoxUserJs(BidiFirefox);
          patchBidiFirefoxNativeViewport(BidiFirefox);
        });
      },
      bidiBrowser: function (BidiBrowser, BidiBrowserContext) {
        return patchOnce(BidiBrowser, function () {
          patchBidiBrowserFirefoxTabs(BidiBrowser, BidiBrowserContext);
          patchBidiBrowserNativeViewport(BidiBrowser);
          patchBidiContextNativePreloadWarning(BidiBrowserContext);
          patchBidiBrowserCacheBehavior(BidiBrowser);
        });
      },
      isRealmDestroyed: isRealmDestroyed,
      isBidiPage: function (delegate) {
        return bidiPageClasses.some(function (BidiPage) { return delegate instanceof BidiPage; });
      },
      // -Dplaywright4j.bidi.unhandledPromptDelay (#48): Playwright answers a dialog nobody handles
      // and an auth challenge without credentials at once; this only delays that same answer.
      closeUnhandledDialog: function (dialog) {
        const delay = global.__playwright4jCoreBundle.isBidiPage(dialog.page().delegate) ? host.unhandledPromptDelayMillis() : -1;
        if (delay < 0) {
          dialog._close().then(function () {});
          return;
        }
        setTimeout(function () {
          // isClosed, not isClosedOrClosing: page.close({ runBeforeUnload }) waits for this answer.
          if (!dialog._handled && !dialog.page().isClosed())
            dialog._close().catch(function () {});
        }, delay);
      },
      onBidiAuthRequired: function (networkManager, params) {
        // A second challenge after Playwright sent the configured credentials means they were
        // wrong; that cancel is the caller's configuration at work and stays immediate.
        const delay = networkManager._attemptedAuthentications.has(params.request.request) ? -1 : host.unhandledPromptDelayMillis();
        if (delay < 0) {
          networkManager._answerAuthRequired(params);
          return;
        }
        // Playwright's own decision runs unchanged; only its "cancel" reaches the browser later.
        const session = networkManager._session;
        const delayedCancel = {
          sendMayFail: function (method, answer) {
            if (answer.action !== 'cancel')
              return session.sendMayFail(method, answer);
            setTimeout(function () { session.sendMayFail(method, answer); }, delay);
          }
        };
        networkManager._answerAuthRequired.call(Object.create(networkManager, { _session: { value: delayedCancel } }), params);
      },
      bidiRead: createBidiReadSession,
      bidiPage: function (BidiPage, nullProgress) {
        return patchOnce(BidiPage, function () {
          patchBidiPageNativeScroll(BidiPage, nullProgress);
          bidiPageClasses.push(BidiPage);
          patchBidiPageInitialBlankLoad(BidiPage);
          patchBidiPageStaleUtilityWorld(BidiPage);
          patchBidiPageRequestGC(BidiPage);
          patchBidiPageTouchUtilityWorld(BidiPage, UTILITY_WORLD_NAME);
          patchBidiPageNoScreenshotStyleToggle(BidiPage);
          patchBidiPageFirefoxLastTab(BidiPage);
          patchBidiPageNativeCaret(BidiPage);
          patchBidiPageNativePreloadWarning(BidiPage);
          patchBidiPageCacheBehavior(BidiPage);
          patchBidiPageNaturalPointer(BidiPage);
        });
      },
      insertTextExpression: insertTextExpression
    };
    const hooks = 'globalThis.__playwright4jCoreBundle';
    // packages/utils/pipeTransport.ts (driver protocol) and server/pipeTransport.ts (CDP over
    // --remote-debugging-pipe, fd 3/4, which the JVM cannot hand to a child process).
    source = replaceCoreBundleAnchor(source, '    PipeTransport = class {', '    PipeTransport = ' + hooks + '.PipeTransport || class {', 1);
    source = replaceCoreBundleAnchor(source, '    PipeTransport2 = class {', '    PipeTransport2 = ' + hooks + '.PipeTransport || class {', 1);
    source = replaceCoreBundleAnchor(source, '    WebSocketTransport = class _WebSocketTransport {',
      '    WebSocketTransport = ' + hooks + '.WebSocketTransport || class _WebSocketTransport {', 1);
    source = replaceCoreBundleAnchor(source, 'new BidiFirefox(this)', 'new (' + hooks + '.bidiFirefox(BidiFirefox))(this)', 1);
    source = replaceCoreBundleAnchor(source, 'BidiBrowser.connect(',
      hooks + '.bidiBrowser(BidiBrowser, BidiBrowserContext2).connect(', 2);
    // Locator actions dispatch __playwright_mark_target__/__playwright_reset_targets__ into the
    // page for the trace viewer's snapshots (#38). Only a trace that records snapshots reads them.
    const snapshotsRecorded = '?.browserContext?.tracing?._snapshotter?.started()';
    source = replaceCoreBundleAnchor(source, '      async _markAsTargetElement(progress2) {\n',
      '      async _markAsTargetElement(progress2) {\n        if (!this._page' + snapshotsRecorded + ')\n          return;\n', 1);
    source = replaceCoreBundleAnchor(source, 'markTargets: options.markTargets }',
      'markTargets: this.frame._page' + snapshotsRecorded + ' ? options.markTargets : void 0 }', 2);
    source = replaceCoreBundleAnchor(source, '        if (!hasHandlers)\n          dialog._close().then(() => {\n          });\n',
      '        if (!hasHandlers)\n          ' + hooks + '.closeUnhandledDialog(dialog);\n', 1);
    source = replaceCoreBundleAnchor(source, '      _onAuthRequired(params2) {\n        const isBasic = ',
      '      _onAuthRequired(params2) {\n        ' + hooks + '.onBidiAuthRequired(this, params2);\n      }\n'
      + '      _answerAuthRequired(params2) {\n        const isBasic = ', 1);
    source = replaceCoreBundleAnchor(source, 'new BidiPage(', 'new (' + hooks + '.bidiPage(BidiPage, nullProgress))(', 1);
    source = replaceCoreBundleAnchor(source,
      '          if (await frame.mainContext() !== mainContext)\n            return;\n          const delegate = new BidiExecutionContext(',
      '          if (await frame.mainContext() !== mainContext || ' + hooks + '.isRealmDestroyed(this, result2.realm))\n'
      + '            return;\n          const delegate = new BidiExecutionContext(', 1);
    // Firefox over BiDi: keep Playwright's own code out of the page's main world, where page
    // scripts see globals and can hook the prototypes it calls (#36, #39, #50). The caller's
    // evaluations and main-world handles stay there, as the API defines.
    // No window.__pw_bidiInsertText on every document: insertText runs its script in the utility world.
    source = replaceCoreBundleAnchor(source, '        promises2.push(this.extendInjectedScript(source7));\n', '', 1);
    source = replaceCoreBundleAnchor(source,
      '          const context2 = await progress2.race(frame.mainContext());\n'
      + '          const handle = await progress2.race(context2.evaluateHandle((text3) => window.__pw_bidiInsertText(text3), text2));',
      '          const context2 = await progress2.race(frame.utilityContext());\n'
      + '          const handle = await progress2.race(context2.evaluateExpressionHandle('
      + hooks + '.insertTextExpression(this._page, source7), { isFunction: true }, text2));', 1);
    source = replaceCoreBundleAnchor(source,
      '        const box = await handle.evaluate((element3) => {\n'
      + '          if (!(element3 instanceof Element) || element3.getClientRects().length === 0)\n'
      + '            return null;\n'
      + '          const rect = element3.getBoundingClientRect();\n'
      + '          return { x: rect.x, y: rect.y, width: rect.width, height: rect.height };\n'
      + '        });\n'
      + '        if (!box)\n',
      '        const box = await handle.evaluateInUtility(([, element3]) => {\n'
      + '          if (!(element3 instanceof Element) || element3.getClientRects().length === 0)\n'
      + '            return null;\n'
      + '          const rect = element3.getBoundingClientRect();\n'
      + '          return { x: rect.x, y: rect.y, width: rect.width, height: rect.height };\n'
      + '        }, {});\n'
      + '        if (!box || box === "error:notconnected")\n', 1);
    // A main-world handle's preview would create the InjectedScript in the main world.
    source = replaceCoreBundleAnchor(source,
      '        const utility = await this._context.injectedScript();\n'
      + '        this._setPreview(await utility.evaluate((injected, e) => "JSHandle@" + injected.previewNode(e), this));',
      '        if (this._context.world === "main" && ' + hooks + '.isBidiPage(this._page.delegate)) {\n'
      + '          const preview = await this.evaluateInUtility(([injected, e]) => "JSHandle@" + injected.previewNode(e), {});\n'
      + '          if (preview !== "error:notconnected")\n'
      + '            this._setPreview(preview);\n'
      + '          return;\n'
      + '        }\n'
      + '        const utility = await this._context.injectedScript();\n'
      + '        this._setPreview(await utility.evaluate((injected, e) => "JSHandle@" + injected.previewNode(e), this));', 1);
    // locator.drop() builds the DataTransfer and
    // dispatches the drag events in the utility world; the page sees the same events (#50).
    source = replaceCoreBundleAnchor(source,
      'return this._retryPointerAction(progress2, "drop", false, async (progress3, point) => {\n'
      + '          const mainContext = await progress3.race(this._frame.mainContext());',
      'return this._retryPointerAction(progress2, "drop", false, async (progress3, point) => {\n'
      + '          const mainContext = await progress3.race(' + hooks + '.isBidiPage(this._page.delegate)'
      + ' ? this._frame.utilityContext() : this._frame.mainContext());', 1);
    // Firefox sends the cached body of a 304 revalidation through network.getData; Response.body()
    // treats every 3xx as a redirect without a body. Only responses from BiDi pages are marked.
    source = replaceCoreBundleAnchor(source,
      '        const response2 = new Response2(request2.request, params2.response.status, params2.response.statusText, fromBidiHeaders(params2.response.headers), timing, getResponseBody, false);\n',
      '        const response2 = new Response2(request2.request, params2.response.status, params2.response.statusText, fromBidiHeaders(params2.response.headers), timing, getResponseBody, false);\n'
      + '        response2._playwright4jBodyOn304 = true;\n', 1);
    source = replaceCoreBundleAnchor(source, '            if (this._status >= 300 && this._status <= 399)\n',
      '            if (this._status >= 300 && this._status <= 399 && !(this._status === 304 && this._playwright4jBodyOn304))\n', 1);
    source = replaceCoreBundleAnchor(source, '      async newCDPSession(params2, progress2) {\n',
      '      async newCDPSession(params2, progress2) {\n'
      + '        if (this._object._browser._browserSession && (params2.page || params2.frame))\n'
      + '          return { session: new CDPSessionDispatcher(this, ' + hooks
      + '.bidiRead(SdkObject, this._object, (params2.page || params2.frame)._object)) };\n', 1);
    // Bundle-internal classes the shim tests drive directly (before 1.62 they were separate
    // modules such as lib/server/utils/wsServer.js).
    source += '\n;' + hooks + '.internals = { WebSocketTransport: WebSocketTransport, BidiFirefox: ' + hooks
      + '.bidiFirefox(BidiFirefox), BidiPage: ' + hooks + '.bidiPage(BidiPage, nullProgress), WSServer: WSServer, createProfile: createProfile, '
      + 'generateSelfSignedCertificate: generateSelfSignedCertificate };\n';
    pw4jDebug('Patched Playwright Core bundle: transports and BiDi classes');
    return source;
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

    // Since Playwright 1.62 the whole server lives in lib/coreBundle.js, so the per-module hooks
    // in installKnownModuleFallbacks no longer see pipeTransport.js, transport.js or the BiDi
    // modules. The same replacements and patches are applied by rewriting the bundle source:
    // the transport classes are swapped at their definition, and the BiDi classes are patched
    // (once, on their prototypes) where Playwright first uses them. Each anchor must occur
    // exactly as often as expected, so a changed bundle fails loudly instead of silently
    // running without a patch.
    if (normalizedResourceName.endsWith('/lib/coreBundle.js')) {
      source = patchCoreBundleSource(source);
    }

    // Route dynamic import() through our CommonJS loader. GraalJS's native dynamic import needs a
    // module loader / IO (disabled here), so `await import("playwright-core/lib/zipBundle")` (used by
    // the trace-zip path) would otherwise throw "Operation is not allowed". Only a bare import(
    // call is rewritten - never `.import(` (a method) or `xImport(`.
    source = source.replace(/(^|[^\w.$])import\s*\(/g, '$1__playwright4jDynamicImport(');

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

      // Bare "playwright-core/..." specifiers resolve to the bundled package (used by the driver's
      // own dynamic import of playwright-core/lib/zipBundle).
      const packageResource = resolvePackageModule(name);
      if (packageResource) {
        return loadCommonJsModule(packageResource);
      }

      // The 1.62 bundle references its package.json using a normalized
      // driver/package/lib/../package.json resource path.
      if (name.indexOf('driver/package/') === 0) {
        const candidate = normalizeResourceName(name);
        if (global.__playwright4jDriverBundleSource.hasResource(candidate)) {
          return loadCommonJsModule(candidate);
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

  // Node-compat dynamic import(): resolve via our CommonJS require and present a Node-style ES
  // module namespace (the exports, plus a `default`). Rewritten import() calls target this.
  global.__playwright4jDynamicImport = function (specifier) {
    return new Promise(function (resolve, reject) {
      try {
        const loaded = global.require(String(specifier));
        if (loaded && loaded.__esModule) {
          resolve(loaded);
          return;
        }
        const namespace = { default: loaded };
        if (loaded && typeof loaded === 'object') {
          for (const key of Object.keys(loaded)) {
            namespace[key] = loaded[key];
          }
        }
        resolve(namespace);
      } catch (error) {
        reject(error);
      }
    });
  };

  global.require = createRequire('');
  global.__playwright4jCreateRequire = createRequire;
  global.__playwright4jDirname = dirname;
  global.__playwright4jModules = modules;
})(globalThis);
