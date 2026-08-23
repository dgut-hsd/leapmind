import type { Config } from "tailwindcss";

export default {
  content: [
    "./app/**/*.{js,ts,jsx,tsx,mdx}",
    "./components/**/*.{js,ts,jsx,tsx,mdx}",
  ],
  theme: {
    extend: {
      colors: {
        ink: "#171717",
        paper: "#f5f2e9",
        acid: "#d8ff62",
        cobalt: "#3157ff",
      },
      boxShadow: {
        hard: "8px 8px 0 #171717",
      },
    },
  },
  plugins: [],
} satisfies Config;

