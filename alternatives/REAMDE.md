In case the app gets unusable, an alternative is found in Google Sheets, using the App Script code of this folder.

Write the task tree in a sheet, and in another, add two buttons.

scripts for each button:
ajouterNoeudMax
remplacerIdParChemin

D3:3 :
time dates

C4 extended on C :
=B4 - ARRAYFORMULA(SOMME(SI(ESTNUM(D$3:3); D4:4 * (0,5 ^ ((AUJOURDHUI() - D$3:3) / 60)); 0))) / $C$2