// PreToolUse guard dos subagents read-only. Uso: node agent-guard.js <git-read|build|docs-only>
// Lê o JSON do hook no stdin; exit 2 bloqueia a chamada e devolve o stderr ao agent.
const path = require('path');

// ./mvnw só com os goals clean, verify e package, mais flags; -pl e -rf levam o valor no token seguinte.
function mavenBuild(command) {
  const [exe, ...tokens] = command.split(/ +/);
  if (!/^(\.\/)?mvnw$/.test(exe) || tokens.length === 0) return false;
  return tokens.every((token, i) =>
    /^(clean|verify|package)$/.test(token) || token.startsWith('-') || /^-(pl|rf|projects)$/.test(tokens[i - 1]));
}

const MODES = {
  // code-reviewer: só inspeção do git.
  'git-read': {
    bash: [/^git (--no-pager )?(status|diff|log|show)( |$)/],
    hint: 'só git status, git diff, git log e git show, sem pipes nem redirecionamento',
  },
  // build-verifier: build Maven e checagem do JDK.
  'build': {
    bash: [
      /^java -?-version$/,
      mavenBuild,
      /^ls( [^ ]+)*$/,
      /^git (status|log -1)( |$)/,
    ],
    hint: './mvnw [clean] verify [-flags], java -version, ls e git status',
  },
  // architect: só ADRs, glossário e specs.
  'docs-only': {
    files: [/^docs\/adr\/[^/]+\.md$/, /^GLOSSARY\.md$/, /^\.scratch\/[^/]+\/spec\.md$/],
    hint: 'só docs/adr/*.md, GLOSSARY.md e .scratch/<feature>/spec.md',
  },
};

function block(reason) {
  process.stderr.write(`Bloqueado pelo agent-guard: ${reason}\n`);
  process.exit(2);
}

let input = '';
process.stdin.on('data', (chunk) => (input += chunk));
// Erro inesperado também bloqueia: o guard falha fechado.
process.on('uncaughtException', (err) => block(`erro interno do guard (${err.message}).`));

process.stdin.on('end', () => {
  const mode = MODES[process.argv[2]];
  if (!mode) block(`modo desconhecido "${process.argv[2]}"`);

  const { tool_name: tool, tool_input: args = {}, cwd } = JSON.parse(input);

  if (tool === 'Bash') {
    const command = String(args.command || '').trim();
    if (!mode.bash) block(`este agent não usa Bash (permitido: ${mode.hint}).`);
    if (/[;&|<>`$\n\r]|--output/.test(command)) block(`"${command}" encadeia, redireciona ou grava arquivo (permitido: ${mode.hint}).`);
    if (!mode.bash.some((rule) => (typeof rule === 'function' ? rule(command) : rule.test(command)))) block(`"${command}" fora do permitido (${mode.hint}).`);
    return process.exit(0);
  }

  if (['Edit', 'Write', 'MultiEdit', 'NotebookEdit'].includes(tool)) {
    if (!mode.files) block(`este agent não edita arquivos.`);
    const root = process.env.CLAUDE_PROJECT_DIR || cwd;
    const file = path.resolve(root, String(args.file_path || args.notebook_path || ''));
    const rel = path.relative(root, file).split(path.sep).join('/');
    const ok = !rel.startsWith('..') && mode.files.some((re) => new RegExp(re.source, 'i').test(rel));
    if (!ok) block(`"${rel}" fora do permitido (${mode.hint}).`);
    return process.exit(0);
  }

  process.exit(0);
});
