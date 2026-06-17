(function installPlaywright4JCompatibilityLayer(global) {
  const modules = {};
  const host = global.__playwright4jHost;

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
      writeFile: unsupported('fs.promises.writeFile'),
      mkdir: unsupported('fs.promises.mkdir'),
      rm: unsupported('fs.promises.rm')
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

  modules.events = {
    EventEmitter: class EventEmitter {
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

      emit(name) {
        const args = Array.prototype.slice.call(arguments, 1);
        const listeners = this.listenersByName[name] || [];
        listeners.slice().forEach(function (listener) {
          listener.apply(null, args);
        });
        return listeners.length > 0;
      }
    }
  };

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
    }
  };

  modules.crypto = {
    randomBytes: function (size) {
      return new Uint8Array(size);
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
    Readable: class Readable {},
    Writable: class Writable {},
    PassThrough: class PassThrough {}
  };

  modules.readline = {
    createInterface: unsupported('readline.createInterface'),
    emitKeypressEvents: unsupported('readline.emitKeypressEvents')
  };

  modules.http = {};
  modules.https = {};
  modules.net = {};
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
    nextTick: function (callback) {
      return Promise.resolve().then(callback);
    },
    versions: {
      node: '0.0.0-playwright4j'
    }
  };

  global.Buffer = {
    from: function (value) {
      if (typeof value === 'string') {
        return new TextEncoder().encode(value);
      }
      return value;
    },
    isBuffer: function () {
      return false;
    },
    alloc: function (size) {
      return new Uint8Array(size);
    }
  };

  global.setImmediate = function (callback) {
    return setTimeout(callback, 0);
  };

  global.clearImmediate = function (handle) {
    return clearTimeout(handle);
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
    const candidates = [
      rawCandidate,
      rawCandidate + '.js',
      rawCandidate + '/index.js'
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

  function installKnownModuleFallbacks(resourceName, exportsObject) {
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

  function loadCommonJsModule(resourceName) {
    const normalizedResourceName = normalizeResourceName(resourceName);

    if (commonJsModuleCache[normalizedResourceName]) {
      return commonJsModuleCache[normalizedResourceName].exports;
    }

    const module = { exports: {} };
    commonJsModuleCache[normalizedResourceName] = module;

    const source = global.__playwright4jDriverBundleSource.readResource(normalizedResourceName);

    if (normalizedResourceName.endsWith('.json')) {
      module.exports = JSON.parse(source);
      module.exports.default = module.exports;
      return module.exports;
    }

    const factory = new Function('require', 'module', 'exports', '__filename', '__dirname', source);
    factory(createRequire(normalizedResourceName), module, module.exports, normalizedResourceName, dirname(normalizedResourceName));
    module.exports = installKnownModuleFallbacks(normalizedResourceName, module.exports);

    return module.exports;
  }

  function createRequire(parentResourceName) {
    return function require(name) {
      const normalizedName = name.indexOf('node:') === 0 ? name.substring(5) : name;

      if (modules[normalizedName]) {
        return modules[normalizedName];
      }

      if (name.indexOf('./') === 0 || name.indexOf('../') === 0) {
        const resolvedResourceName = resolveRelativeModule(name, parentResourceName);

        if (resolvedResourceName) {
          return loadCommonJsModule(resolvedResourceName);
        }
      }

      reportMissing('require(' + name + ')');
      throw new Error('Unsupported Playwright4J module: ' + name);
    };
  }

  global.require = createRequire('');
  global.__playwright4jCreateRequire = createRequire;
  global.__playwright4jDirname = dirname;
  global.__playwright4jModules = modules;
})(globalThis);
