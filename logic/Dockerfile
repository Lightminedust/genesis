# Utilise une image Python légère
FROM python:3.11-slim

# Définit le dossier de travail dans le conteneur
WORKDIR /app/logic

# Les paquets téléchargés restent dans un cache persistant (--mount=type=cache) :
# un nouveau build ne retélécharge que ce qui a changé. Délais longs et reprises :
# une connexion lente ne fait pas échouer l'installation.
ENV PIP_DEFAULT_TIMEOUT=120 \
    PIP_RETRIES=10

# Dépendances du moteur (atome, noyau, collision, étoile).
COPY requirements.txt .
RUN --mount=type=cache,target=/root/.cache/pip pip install -r requirements.txt

# Chimie quantique (échelle « Molécule ») : le plus gros paquet, dans sa propre
# couche pour qu'un changement ailleurs ne le retélécharge pas.
COPY requirements-chemistry.txt .
RUN --mount=type=cache,target=/root/.cache/pip pip install -r requirements-chemistry.txt

# Copie le code source Python
COPY . .

# Le .proto n'est PAS dans le contexte de build (il vit dans ../core) :
# il est monté par docker-compose et compilé au démarrage par l'entrypoint.
RUN chmod +x /app/logic/entrypoint.sh

# Expose le port gRPC
EXPOSE 50051

CMD ["/app/logic/entrypoint.sh"]
