import { sveltekit } from '@sveltejs/kit/vite';
import tailwindcss from '@tailwindcss/vite';
import { defineConfig } from 'vite';

export default defineConfig({
  plugins: [tailwindcss(), sveltekit()],
  resolve: {
    alias: [
      // monaco-editor >= 0.56 ships an `exports` map that hides the `esm/vs/` deep paths
      // still used by monaco-yaml's dependencies (monaco-worker-manager, monaco-types).
      { find: /^monaco-editor\/esm\/vs\/(.*)$/, replacement: 'monaco-editor/$1' }
    ]
  },
  worker: {
    format: 'es'
  }
});

