declare global {
  function fetch(
    input: RequestInfo | URL,
    init?: RequestInit
  ): Promise<Response>;

  var console: {
    log: (...args: any[]) => void;
    error: (...args: any[]) => void;
    warn: (...args: any[]) => void;
    info: (...args: any[]) => void;
  };
}

export {};