(function installPlaywright4JCompatibilityLayer(global) {
  const modules = {};
  const host = global.__playwright4jHost;

  global.global = global;

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
      if (!hostExists(path)) {
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
    child.pid = Math.floor(Math.random() * 1000000) + 1;
    child.killed = false;
    child.__playwright4jProcessId = processId;

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
    child.kill = function () {
      if (!child.killed) {
        child.killed = true;
        try {
          host.processLauncher().close(processId);
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
        entry.child.emit('exit', code, null);
        entry.child.emit('close', code, null);
        delete generalProcessesById[parts[1]];
      }
      handled++;
    }
    return handled;
  };

  modules.child_process = {
    spawn: function (command, args, options) {
      const argList = (args || []).map(function (value) { return String(value); });
      const joinedArguments = argList.join(ARGUMENT_SEPARATOR);
      const workingDirectory = options && options.cwd ? String(options.cwd) : global.process.cwd();

      // A Chromium launch is identified by its remote-debugging transport flag; everything else
      // (notably the ffmpeg video encoder) is a general subprocess fed via stdin.
      const isChromium = argList.some(function (value) {
        return value.indexOf('--remote-debugging') === 0;
      });
      if (!isChromium) {
        const generalId = String(host.processLauncher().spawnProcess(String(command), joinedArguments, workingDirectory));
        return createGeneralProcess(generalId);
      }

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

  EventEmitter.prototype.__listeners = function () {
    if (!this.listenersByName) {
      this.listenersByName = {};
    }
    return this.listenersByName;
  };

  EventEmitter.prototype.on = function (name, listener) {
    const registry = this.__listeners();
    registry[name] = registry[name] || [];
    registry[name].push(listener);
    return this;
  };

  EventEmitter.prototype.addListener = function (name, listener) {
    return this.on(name, listener);
  };

  EventEmitter.prototype.prependListener = function (name, listener) {
    const registry = this.__listeners();
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
    const registry = this.__listeners();
    const listeners = registry[name] || [];
    registry[name] = listeners.filter(function (candidate) {
      return candidate !== listener && candidate.__originalListener !== listener;
    });
    return this;
  };

  EventEmitter.prototype.removeListener = function (name, listener) {
    return this.off(name, listener);
  };

  EventEmitter.prototype.removeAllListeners = function (name) {
    const registry = this.__listeners();
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
    return Object.keys(this.__listeners());
  };

  EventEmitter.prototype.listeners = function (name) {
    return (this.__listeners()[name] || []).slice();
  };

  EventEmitter.prototype.rawListeners = function (name) {
    return (this.__listeners()[name] || []).slice();
  };

  EventEmitter.prototype.listenerCount = function (name) {
    return (this.__listeners()[name] || []).length;
  };

  EventEmitter.prototype.emit = function (name) {
    const self = this;
    const args = Array.prototype.slice.call(arguments, 1);
    const listeners = (this.__listeners()[name] || []).slice();
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

  modules.crypto = {
    randomBytes: function (size) {
      const bytes = global.Buffer.alloc(size);

      for (let index = 0; index < size; index++) {
        bytes[index] = Math.floor(Math.random() * 256);
      }

      return bytes;
    },
    createHash: function (algorithm) {
      return createHostHash(algorithm);
    },
    randomUUID: function () {
      const bytes = global.Buffer.alloc(16);
      for (let index = 0; index < 16; index++) {
        bytes[index] = Math.floor(Math.random() * 256);
      }
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
  function Readable(options) {
    EventEmitter.call(this);
    this.readable = true;
    this._readableBuffer = [];
    this._flowing = false;
    this._readableEnded = false;
    this._reading = false;
    // Some bundled readable-stream consumers (e.g. fd-slicer's zip entry reader) read
    // this._readableState.highWaterMark to size their reads.
    this._readableState = { highWaterMark: 16384, flowing: false, ended: false };
    if (options && typeof options.read === 'function') {
      this._read = options.read;
    }
    if (options && typeof options.highWaterMark === 'number') {
      this._readableState.highWaterMark = options.highWaterMark;
    }
  }
  Readable.prototype = Object.create(EventEmitter.prototype);
  Readable.prototype.constructor = Readable;

  // Pull-based source streams (e.g. fd-slicer's zip entry reader) implement _read() and only
  // produce data when it is invoked. Drive _read while flowing so such streams actually emit
  // their bytes and reach end; without this the zip read pipeline never completes.
  Readable.prototype._maybeRead = function () {
    if (this._readableEnded || this._reading || !this._flowing || typeof this._read !== 'function') {
      return;
    }
    this._reading = true;
    try {
      this._read(65536);
    } catch (error) {
      this.emit('error', error);
    }
    this._reading = false;
  };

  Readable.prototype.push = function (chunk) {
    if (chunk === null) {
      this._readableEnded = true;
      // Keep `readable` true until the buffer is drained so pull-mode consumers can finish
      // reading any buffered bytes; flowing-mode consumers get 'end' immediately.
      if (this._flowing) {
        this.readable = false;
        this.emit('end');
      } else {
        this.emit('readable');
      }
      return false;
    }
    this._readableBuffer.push(chunk);
    if (this._flowing) {
      while (this._readableBuffer.length > 0) {
        this.emit('data', this._readableBuffer.shift());
      }
      // Ask the source for more (Node calls _read again after each consumed chunk).
      this._maybeRead();
    } else {
      // Pull mode: notify consumers that data is available to read().
      this.emit('readable');
    }
    return true;
  };

  // Pull-based read used by Playwright's StreamDispatcher (download.createReadStream): returns
  // up to `size` buffered bytes, or null when nothing is currently buffered.
  Readable.prototype.read = function (size) {
    if (this._readableBuffer.length === 0) {
      if (this._readableEnded) {
        this.readable = false;
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
      if (this._readableBuffer.length === 0 && this._readableEnded) {
        this.readable = false;
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
      return this._readableEnded && this._readableBuffer.length === 0;
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
      this.resume();
    } else if (name === 'readable' && (this._readableBuffer.length > 0 || this._readableEnded)) {
      // Node re-signals readability to listeners attached after data is already buffered (or
      // after end). Playwright's StreamDispatcher attaches 'readable'/'end' per read() call,
      // often after createReadStream already pushed everything, so replay the signal async.
      const self = this;
      Promise.resolve().then(function () { self.emit('readable'); });
    } else if (name === 'end' && this._readableEnded && this._readableBuffer.length === 0) {
      const self = this;
      Promise.resolve().then(function () { self.emit('end'); });
    }
    return result;
  };

  Readable.prototype.resume = function () {
    if (this._flowing) {
      return this;
    }
    this._flowing = true;
    while (this._readableBuffer.length > 0) {
      this.emit('data', this._readableBuffer.shift());
    }
    if (this._readableEnded) {
      this.readable = false;
      this.emit('end');
    } else {
      // Kick off pull-based sources (_read) now that we are flowing.
      this._maybeRead();
    }
    return this;
  };

  Readable.prototype.pause = function () {
    this._flowing = false;
    return this;
  };

  Readable.prototype.setEncoding = function () {
    return this;
  };

  Readable.prototype.pipe = function (destination) {
    const self = this;
    this.on('data', function (chunk) {
      if (destination && typeof destination.write === 'function') {
        destination.write(chunk);
      }
    });
    this.on('end', function () {
      if (destination && typeof destination.end === 'function') {
        destination.end();
      }
    });
    this.on('error', function (error) {
      if (destination && typeof destination.emit === 'function') {
        destination.emit('error', error);
      }
    });
    if (destination && typeof destination.emit === 'function') {
      destination.emit('pipe', self);
    }
    return destination;
  };

  Readable.prototype.destroy = function (error) {
    this._readableEnded = true;
    if (error) {
      this.emit('error', error);
    }
    this.emit('close');
    return this;
  };

  function Writable(options) {
    EventEmitter.call(this);
    this.writable = true;
    this._writableEnded = false;
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

  Writable.prototype.write = function (chunk, encoding, callback) {
    if (typeof encoding === 'function') {
      callback = encoding;
      encoding = undefined;
    }
    const self = this;
    try {
      if (typeof this._write === 'function') {
        this._write(chunk, encoding, function (error) {
          if (error) {
            self.emit('error', error);
          }
          if (callback) {
            callback(error);
          }
        });
      } else if (callback) {
        callback();
      }
    } catch (error) {
      this.emit('error', error);
    }
    return true;
  };

  Writable.prototype.end = function (chunk, encoding, callback) {
    if (typeof chunk === 'function') {
      callback = chunk;
      chunk = undefined;
    } else if (typeof encoding === 'function') {
      callback = encoding;
      encoding = undefined;
    }
    const self = this;
    const finish = function () {
      self._writableEnded = true;
      self.fireSticky('finish');
      self.fireSticky('close');
      if (callback) {
        callback();
      }
    };
    const afterWrite = function () {
      if (typeof self._final === 'function') {
        self._final(function () { finish(); });
      } else {
        finish();
      }
    };
    if (chunk !== undefined && chunk !== null) {
      this.write(chunk, encoding, afterWrite);
    } else {
      afterWrite();
    }
    return this;
  };

  Writable.prototype.destroy = function (error) {
    if (error) {
      this.emit('error', error);
    }
    this.emit('close');
    return this;
  };

  // Transform is both readable and writable: written chunks pass through _transform and are
  // pushed to the readable side.
  function Transform(options) {
    Readable.call(this, options);
    this.writable = true;
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
    const doEnd = function () {
      if (typeof self._flush === 'function') {
        self._flush(function (error, data) {
          if (data !== undefined && data !== null) {
            self.push(data);
          }
          self.push(null);
          self.emit('finish');
          if (callback) {
            callback(error);
          }
        });
      } else {
        self.push(null);
        self.emit('finish');
        if (callback) {
          callback();
        }
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
  // honouring the { read, write, destroy, final } option callbacks. Distinct from Transform (whose
  // write feeds its own readable side). Playwright's client-certificate interceptor builds
  // `new stream.Duplex({ read, write, destroy })` whose write must forward bytes to the SOCKS proxy
  // rather than loop back into the readable side.
  function Duplex(options) {
    Readable.call(this, options);
    this.writable = true;
    if (options && typeof options.write === 'function') {
      this._writeImpl = options.write;
    }
    if (options && typeof options.final === 'function') {
      this._finalImpl = options.final;
    }
    if (options && typeof options.destroy === 'function') {
      this._destroyImpl = options.destroy;
    }
  }
  Duplex.prototype = Object.create(Readable.prototype);
  Duplex.prototype.constructor = Duplex;

  Duplex.prototype.write = function (chunk, encoding, callback) {
    if (typeof encoding === 'function') {
      callback = encoding;
      encoding = undefined;
    }
    const self = this;
    if (typeof this._writeImpl === 'function') {
      this._writeImpl(chunk, encoding, function (error) {
        if (error) {
          self.emit('error', error);
        }
        if (callback) {
          callback(error);
        }
      });
    } else if (callback) {
      callback();
    }
    return true;
  };

  Duplex.prototype.end = function (chunk, encoding, callback) {
    if (typeof chunk === 'function') {
      callback = chunk;
      chunk = undefined;
    } else if (typeof encoding === 'function') {
      callback = encoding;
      encoding = undefined;
    }
    const self = this;
    const finish = function () {
      const done = function () {
        self.writable = false;
        self.emit('finish');
        if (callback) {
          callback();
        }
      };
      if (typeof self._finalImpl === 'function') {
        self._finalImpl(done);
      } else {
        done();
      }
    };
    if (chunk !== undefined && chunk !== null) {
      this.write(chunk, encoding, finish);
    } else {
      finish();
    }
    return this;
  };

  Duplex.prototype.destroy = function (error) {
    if (this._destroyed) {
      return this;
    }
    this._destroyed = true;
    const self = this;
    const done = function (laterError) {
      const failure = laterError || error;
      if (failure) {
        self.emit('error', failure);
      }
      self.emit('close');
    };
    if (typeof this._destroyImpl === 'function') {
      this._destroyImpl(error || null, done);
    } else {
      done();
    }
    return this;
  };

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
        requestId = host.httpClient().startRequest(method, url, flattenRequestHeaders(options), bodyBase64, extractTlsOptions(options));
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

  class NetSocket extends EventEmitter {
    constructor(socketId) {
      super();
      this.__socketId = socketId;
      this.__closed = false;
      this.readable = true;
      this.writable = true;
      this.destroyed = false;
      this.writableEnded = false;
      this.readableEnded = false;
      this.localAddress = undefined;
      this.localPort = undefined;
      this.remoteAddress = undefined;
      this.remotePort = undefined;
    }

    get readyState() {
      if (this.__closed) {
        return 'closed';
      }
      return this.readable && this.writable ? 'open' : (this.writable ? 'readOnly' : 'writeOnly');
    }

    // Emits the readable-EOF and terminal 'close' exactly once. Node's stream/ws close handshake
    // relies on 'end' then 'close' firing; the bundled ws socketOnClose (via 'close') is what makes
    // the server WebSocket emit its own 'close' so Playwright-Core cleans up the connection.
    __finishClose(hadError, emitEnd) {
      if (this.__closed) {
        return;
      }
      this.__closed = true;
      this.destroyed = true;
      this.readable = false;
      this.writable = false;
      this.readableEnded = true;
      const self = this;
      if (emitEnd) {
        self.emit('end');
      }
      if (hadError) {
        // 'error' listeners must exist; ws attaches socketOnError. Emit before 'close' (Node order).
        // (Caller passes the error object via emit('error') separately.)
      }
      self.emit('close', !!hadError);
    }

    write(chunk, encoding, callback) {
      if (typeof encoding === 'function') {
        callback = encoding;
        encoding = undefined;
      }
      const buffer = global.Buffer.isBuffer(chunk)
        ? chunk
        : global.Buffer.from(String(chunk), typeof encoding === 'string' ? encoding : 'utf8');
      if (this.__socketId) {
        host.netServer().write(this.__socketId, buffer.toString('base64'));
      }
      if (callback) {
        callback();
      }
      return true;
    }

    end(chunk, encoding, callback) {
      if (typeof chunk === 'function') {
        callback = chunk;
        chunk = undefined;
      } else if (typeof encoding === 'function') {
        callback = encoding;
        encoding = undefined;
      }
      if (chunk !== undefined && chunk !== null) {
        this.write(chunk, encoding);
      }
      if (!this.writableEnded) {
        this.writableEnded = true;
        this.writable = false;
        const self = this;
        Promise.resolve().then(function () { self.emit('finish'); });
      }
      // ws's socketOnEnd calls socket.end() to half-close; once we've written our side, close the
      // host socket so the peer gets a FIN and the connection actually terminates.
      if (this.__socketId) {
        host.netServer().closeSocket(this.__socketId);
        this.__socketId = null;
      }
      const self = this;
      Promise.resolve().then(function () { self.__finishClose(false, false); });
      if (callback) {
        callback();
      }
      return this;
    }

    destroy(error) {
      if (this.__socketId) {
        host.netServer().closeSocket(this.__socketId);
        this.__socketId = null;
      }
      const self = this;
      Promise.resolve().then(function () {
        if (error) {
          self.emit('error', error);
        }
        self.__finishClose(!!error, false);
      });
      return this;
    }

    setEncoding() { return this; }
    setNoDelay() { return this; }
    setKeepAlive() { return this; }
    setTimeout() { return this; }
    pause() { return this; }
    resume() { return this; }
    // The bundled ws Sender batches frame header + payload with cork()/uncork(); without these
    // (no-ops here, our write() flushes immediately) it throws mid-send and the connection breaks.
    cork() { return this; }
    uncork() { return this; }
    ref() { return this; }
    unref() { return this; }
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

  global.__playwright4jDrainNet = function () {
    const batch = String(host.netServer().drainEvents() || '');
    if (batch.length === 0) {
      return 0;
    }
    const lines = batch.split('\n');
    let handled = 0;
    for (let index = 0; index < lines.length; index++) {
      const parts = lines[index].split(NET_UNIT);
      const type = parts[0];
      if (type === 'connection') {
        const server = netServersById[parts[1]];
        const socket = new NetSocket(parts[2]);
        netSocketsById[parts[2]] = socket;
        if (server) {
          server.emit('connection', socket);
        }
        handled++;
      } else if (type === 'data') {
        const socket = netSocketsById[parts[1]];
        if (socket) {
          socket.emit('data', global.Buffer.from(parts[2] || '', 'base64'));
        }
        handled++;
      } else if (type === 'close') {
        // Peer closed (FIN). Emit readable 'end' then the terminal 'close' exactly once, so the ws
        // socketOnEnd/socketOnClose handlers run and the server WebSocket cleans up the connection.
        const socket = netSocketsById[parts[1]];
        if (socket) {
          socket.__socketId = null;
          socket.__finishClose(false, true);
          delete netSocketsById[parts[1]];
        }
        handled++;
      } else if (type === 'connect') {
        // Outbound connection established (net.createConnection).
        const socket = netSocketsById[parts[1]];
        if (socket) {
          socket.localAddress = parts[2];
          socket.localPort = parseInt(parts[3], 10);
          socket.remoteAddress = parts[4];
          socket.remotePort = parseInt(parts[5], 10);
          socket.emit('connect');
        }
        handled++;
      } else if (type === 'connecterror') {
        const socket = netSocketsById[parts[1]];
        if (socket) {
          const error = new Error(parts[2] || 'connect failed');
          error.code = 'ECONNREFUSED';
          socket.emit('error', error);
          delete netSocketsById[parts[1]];
        }
        handled++;
      }
    }
    return handled;
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
      const socket = new NetSocket(socketId);
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
        if (String(request.headers['upgrade'] || '').toLowerCase() === 'websocket') {
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
    // is listening (an unhandled 'error' would throw out of the pump), otherwise just tear down.
    const fail = function (error) {
      if (socket.listenerCount && socket.listenerCount('error') > 0) {
        socket.emit('error', error);
      }
      try { host.tls().closeEngine(engineId); } catch (ignored) {}
      socket.push(null);
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
    underlying.on('close', function () { socket.push(null); socket.emit('close'); });
    underlying.on('error', function (error) { fail(error); });
    socket.__kickHandshake = function () { feed(''); };
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
        traceWebSocket('transport-send:' + JSON.stringify(message));
        host.webSocketClient().send(this.connectionId, JSON.stringify(message));
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

      close() {
        const index = activeTransports.indexOf(this);
        if (index >= 0) {
          activeTransports.splice(index, 1);
        }
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
