import type { Metadata } from "next";
import "./globals.css";

export const metadata: Metadata = {
  title: "FormFoundry — AI Image to 3D",
  description: "Generate, process and preview GLB assets from text or images.",
};

export default function RootLayout({
  children,
}: Readonly<{ children: React.ReactNode }>) {
  return (
    <html lang="zh-CN">
      <body>{children}</body>
    </html>
  );
}

