// The status page is public (FR-7.6), and adapter messages are not written for
// an audience: a Playwright timeout arrives as a multi-line stack carrying the
// absolute paths of whatever machine ran the pipeline. Keep the part that says
// what went wrong, drop the part that describes the library to itself.

const WINDOWS_PATH = /[A-Za-z]:\\[^\s'")]+/g;
const POSIX_PATH = /\/(?:home|Users|opt|tmp)\/[^\s'")]+/g;
/** Where a library stops describing the problem and starts describing itself. */
const STACK_MARKER = /\s(?:stack=|name=|at\s+[\w.$]+\()/;
/** Playwright's Java wrapper wraps the useful sentence in object syntax. */
const WRAPPER = /^Error\s*\{\s*message='?/;

export function tidyMessage(message: string, maxLength = 160): string {
  const flat = message.replace(/\s+/g, ' ').trim();

  // Keep any "<url>: " prefix an adapter added, then tidy the reason after it.
  const split = flat.indexOf(': ');
  const prefix = split > 0 && /^https?:\/\//.test(flat) ? flat.slice(0, split + 2) : '';
  const reason = (prefix ? flat.slice(prefix.length) : flat)
    .split(STACK_MARKER)[0]
    .replace(WRAPPER, '')
    .replace(WINDOWS_PATH, '…')
    .replace(POSIX_PATH, '…')
    .replace(/[\s'"{,.]+$/, '')
    .trim();

  const cleaned = (prefix + reason).trim();
  return cleaned.length > maxLength ? `${cleaned.slice(0, maxLength - 1)}…` : cleaned;
}
