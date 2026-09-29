import "./globals.css";
import Nav from "../components/Nav";

export const metadata = {
  title: "Registre EB/CDC — Assistant RAG métier",
  description:
    "Suivi des expressions de besoin et cahiers des charges, assistance à la rédaction et audit des délais prestataires.",
};

export default function RootLayout({ children }) {
  return (
    <html lang="fr">
      <body>
        <Nav />
        <main className="container">{children}</main>
      </body>
    </html>
  );
}
