-- PHASE 33 (ADR-040). «Apri con»: a program of the Software Hub can be asked to open
-- one file of a project, and the console suggests the one that suits the file.
--
-- file_args: the arguments that open a file, one per line, after the catalog's own
--   executable_args. '{file}' is the file and must be there exactly once; '{folder}' is
--   the project's folder. Both are whole arguments, filled by the control plane with
--   paths it resolved itself (ADR-019 I2): nothing of a request lands on the command
--   line as text. NULL: the program does not open files from here.
-- file_types: what the program is the right choice for, comma separated like the other
--   tag columns: '.java' an extension, 'pom.xml' a file name, '*' any text file. It ranks
--   the suggestion; it never limits what the operator may choose.
--
-- Additive: two columns and a check every existing row passes (file_args is NULL).
ALTER TABLE software ADD COLUMN file_args VARCHAR(300);
ALTER TABLE software ADD COLUMN file_types VARCHAR(1000) NOT NULL DEFAULT '';

-- A file is passed only to an executable the catalog names: never through a Start-menu
-- AppID (explorer.exe takes no argument for it), never to a command-line tool.
ALTER TABLE software ADD CONSTRAINT software_file_args_check CHECK (
    file_args IS NULL
    OR (launch_kind = 'DESKTOP' AND executable IS NOT NULL AND file_args LIKE '%{file}%'));

-- The catalog file only inserts missing keys (ADR-018 §4): the shipped programs that open
-- files get the new fields here, on databases created before them. A row the operator
-- turned into something else (no executable any more) keeps what it has. The row's
-- version moves too, so a tag read before this migration cannot write the fields away.
UPDATE software SET file_args = E'{folder}\n{file}', file_types = '', updated_at = now(), version = version + 1
WHERE key = 'antigravity-ide' AND launch_kind = 'DESKTOP' AND executable IS NOT NULL AND file_args IS NULL;

UPDATE software SET file_args = E'{file}', file_types = '.java,.kt,.kts,.groovy,.gradle,.properties,.sql,pom.xml,'
    || 'build.gradle,build.gradle.kts,settings.gradle,settings.gradle.kts', updated_at = now(), version = version + 1
WHERE key = 'intellij-junie' AND launch_kind = 'DESKTOP' AND executable IS NOT NULL AND file_args IS NULL;

UPDATE software SET file_args = E'{folder}\n{file}', file_types = '.md,.mdx,.json,.jsonc,.json5,.yml,.yaml,.toml,.xml,'
    || '.xsd,.svg,.ts,.tsx,.js,.jsx,.mjs,.cjs,.css,.scss,.html,.py,.java,.go,.rs,.rb,.php,.sh,.bash,.ps1,.psm1,.bat,'
    || '.cmd,.sql,.graphql,.prisma,.env,.gitignore,.gitattributes,.editorconfig,.dockerignore,dockerfile,'
    || 'docker-compose.yml,makefile,*', updated_at = now(), version = version + 1
WHERE key = 'vscode-continue' AND launch_kind = 'DESKTOP' AND executable IS NOT NULL AND file_args IS NULL;

UPDATE software SET file_args = E'--open\n{file}', file_types = '.jsp,.jspf,.tag,.tagx,.tld,.form,build.xml,nbactions.xml',
    updated_at = now(), version = version + 1
WHERE key = 'netbeans' AND launch_kind = 'DESKTOP' AND executable IS NOT NULL AND file_args IS NULL;

UPDATE software SET file_args = E'{file}', file_types = '.py,.pyi,.ipynb,requirements.txt,pyproject.toml,pipfile,'
    || 'setup.cfg,tox.ini', updated_at = now(), version = version + 1
WHERE key = 'pycharm' AND launch_kind = 'DESKTOP' AND executable IS NOT NULL AND file_args IS NULL;

UPDATE software SET file_args = E'{file}', file_types = '.sln,.slnx,.csproj,.vbproj,.fsproj,.vcxproj,.props,.targets,'
    || '.cs,.vb,.fs,.c,.cpp,.cc,.cxx,.h,.hpp,.xaml,.razor,.cshtml,.resx', updated_at = now(), version = version + 1
WHERE key = 'visual-studio-2022' AND launch_kind = 'DESKTOP' AND executable IS NOT NULL AND file_args IS NULL;

UPDATE software SET file_args = E'{file}', file_types = '.ts,.tsx,.js,.jsx,.mjs,.cjs,.vue,.svelte,.astro,.css,.scss,'
    || '.sass,.less,.html,.htm,package.json,tsconfig.json', updated_at = now(), version = version + 1
WHERE key = 'webstorm' AND launch_kind = 'DESKTOP' AND executable IS NOT NULL AND file_args IS NULL;

UPDATE software SET file_args = E'{file}', file_types = '.txt,.log,.ini,.cfg,.conf,.csv,.tsv,.nfo,*', updated_at = now(), version = version + 1
WHERE key = 'notepad-plus-plus' AND launch_kind = 'DESKTOP' AND executable IS NOT NULL AND file_args IS NULL;

UPDATE software SET file_args = E'{file}', file_types = '.mwb', updated_at = now(), version = version + 1
WHERE key = 'mysql-workbench' AND launch_kind = 'DESKTOP' AND executable IS NOT NULL AND file_args IS NULL;

UPDATE software SET file_args = E'{file}', file_types = '.blend', updated_at = now(), version = version + 1
WHERE key = 'blender' AND launch_kind = 'DESKTOP' AND executable IS NOT NULL AND file_args IS NULL;
