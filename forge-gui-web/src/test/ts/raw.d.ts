// A file imported with ?raw, which Vite hands over as its text
declare module '*?raw' {
  const text: string;
  export default text;
}
