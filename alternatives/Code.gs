// Nom de la feuille qui contient l'arbre.
const NOM_FEUILLE_ARBRE = "task tree"; 
// La ligne à partir de laquelle on vérifie si le chemin existe (ex: 4 pour ignorer un en-tête)
const LIGNE_DEBUT_VERIFICATION = 4;

// Définition centralisée des couleurs
const COULEUR_BLEU = "#4a86e8";
const COULEUR_ROUGE = "#ff0000";
const COULEUR_VERT = "#00ff00";

// Ajout d'un paramètre 'isSilent' pour ne pas spammer de notifications quand appelée par une autre fonction
function appliquerFormuleADroiteNomFeuille(isSilent) {
  var classeur = SpreadsheetApp.getActiveSpreadsheet();
  var feuille = classeur.getSheetByName(NOM_FEUILLE_ARBRE);
  
  if (!feuille) {
    if (!isSilent) classeur.toast("Erreur : L'onglet '" + NOM_FEUILLE_ARBRE + "' est introuvable.");
    return; 
  }
  
  var derniereLigne = feuille.getLastRow();
  var derniereColonne = feuille.getLastColumn();
  
  if (derniereLigne === 0 || derniereColonne === 0) return;
  
  var valeurs = feuille.getRange(1, 1, derniereLigne, derniereColonne).getValues();
  var compteur = 0;
  
  for (var i = 1; i < derniereLigne; i++) { 
    for (var j = 0; j < derniereColonne; j++) { 
      
      var celluleActuelle = valeurs[i][j];
      
      if (typeof celluleActuelle === 'string' && celluleActuelle.trim() !== "") {
        
        // NOUVEAUTÉ : Si on est dans la première colonne (index 0)
        if (j === 0) {
          var celluleCible = feuille.getRange(i + 1, j + 2);
          var formule = "=RC[1]"; // Formule simple, sans parent
          
          celluleCible.setFormulaR1C1(formule);
          valeurs[i][j + 1] = 0; 
          compteur++;
        } 
        // Si on est dans les autres colonnes (index > 0)
        else {
          var scanLigne = i - 1; 
          var decalageLigne = null;
          
          while (scanLigne >= 0) {
            var testValeur = valeurs[scanLigne][j];
            
            if (typeof testValeur === 'number') {
              decalageLigne = scanLigne - i; 
              break; 
            }
            scanLigne--;
          }
          
          if (decalageLigne !== null) {
            var celluleCible = feuille.getRange(i + 1, j + 2);
            var formule = "=R[" + decalageLigne + "]C[-1]*RC[1]"; // Formule avec parent
            
            celluleCible.setFormulaR1C1(formule);
            valeurs[i][j + 1] = 0; 
            compteur++;
          }
        }
        
        // MODIFICATION CRUCIALE : On a trouvé le nœud de cette ligne, on ignore tout texte éventuel à droite
        break; 
      }
    }
  }
  
  if (!isSilent) {
    classeur.toast(compteur + " formule(s) appliquée(s) sur l'onglet " + NOM_FEUILLE_ARBRE + " !");
  }
}


// --- FONCTION PRINCIPALE DE VÉRIFICATION ET REMPLACEMENT ---
function remplacerIdParChemin(returnContext) {
  // 1. MISE À JOUR DE L'ARBRE (Appel silencieux)
  appliquerFormuleADroiteNomFeuille(true);
  
  // Force Google Sheets à calculer les formules qu'on vient d'ajouter 
  SpreadsheetApp.flush(); 

  var classeur = SpreadsheetApp.getActiveSpreadsheet();
  var feuilleArbre = classeur.getSheetByName(NOM_FEUILLE_ARBRE);
  var feuilleTraitement = classeur.getActiveSheet(); 
  
  if (!feuilleArbre) {
    if (!returnContext) SpreadsheetApp.getUi().alert("Feuille contenant l'arbre introuvable.");
    return null;
  }
  
  // 2. Charger l'arbre et recenser toutes les feuilles valides
  var donneesArbre = feuilleArbre.getDataRange().getValues();
  var dictionnaireFeuilles = new Map(); 
  
  for (var r = 0; r < donneesArbre.length; r++) {
    for (var c = 0; c < donneesArbre[r].length; c++) {
      var val = donneesArbre[r][c];
      
      if (val !== "" && typeof val === 'string' && val.trim() !== "") {
        if (estFeuille(donneesArbre, r, c)) {
          var chemin = calculerCheminGrille(donneesArbre, r + 1, c + 1);
          var valDroite = (c + 1 < donneesArbre[r].length) ? donneesArbre[r][c + 1] : "";
          var isNumber = (typeof valDroite === 'number');
          var refA1 = numVersLettreColonne(c + 2) + (r + 1); 
          
          dictionnaireFeuilles.set(chemin, {
            ref: refA1,
            valeur: isNumber ? valDroite : -Infinity,
            isNumber: isNumber
          });
        }
        // MODIFICATION CRUCIALE : On a traité le vrai nœud, on s'arrête pour cette ligne
        break;
      }
    }
  }

  var lastRow = Math.max(feuilleTraitement.getLastRow(), LIGNE_DEBUT_VERIFICATION);
  var rangeColA = feuilleTraitement.getRange(1, 1, lastRow, 1);
  var rangeColB = feuilleTraitement.getRange(1, 2, lastRow, 1);
  var rangeColC = feuilleTraitement.getRange(1, 3, lastRow, 1); 
  
  var valeursA = rangeColA.getValues();
  var couleursA = rangeColA.getBackgrounds();
  var formulesB = rangeColB.getFormulas();
  var valeursC = rangeColC.getValues();
  
  var cheminsExistants = new Set();
  var infosExistants = new Map(); 
  
  var derniereLigneRemplie = LIGNE_DEBUT_VERIFICATION - 1;
  var nomFeuilleEchappe = "'" + feuilleArbre.getName().replace(/'/g, "''") + "'"; 
  var regexId = /^[A-Za-z]+[0-9]+$/;
  
  // 3. LECTURE DE HAUT EN BAS
  for (var i = LIGNE_DEBUT_VERIFICATION - 1; i < lastRow; i++) {
    var texteCellule = valeursA[i][0].toString().trim();
    
    if (texteCellule !== "") {
      derniereLigneRemplie = Math.max(derniereLigneRemplie, i + 1);
      
      var estValide = false;
      var cheminEvalue = texteCellule;
      var refCible = "";
      
      if (dictionnaireFeuilles.has(texteCellule)) {
        estValide = true;
        refCible = dictionnaireFeuilles.get(texteCellule).ref;
      } 
      else if (regexId.test(texteCellule)) {
        var coord = idVersCoordonnees(texteCellule);
        var rId = coord.row - 1;
        
        if (rId >= 0 && rId < donneesArbre.length) {
          var colTexte = -1;
          
          // MODIFICATION CRUCIALE : On trouve le vrai nœud en scannant la ligne de gauche à droite
          for (var c = 0; c < donneesArbre[rId].length; c++) {
            var noeudVal = donneesArbre[rId][c];
            if (noeudVal !== "" && typeof noeudVal === 'string' && noeudVal.trim() !== "") {
              colTexte = c;
              break; // On a trouvé le texte du nœud !
            }
          }

          // Si on a bien fini par trouver un texte sur cette ligne
          if (colTexte >= 0) {
            
            // On vérifie que le noeud est bien une feuille de l'arbre
            if (estFeuille(donneesArbre, rId, colTexte)) {
              
              // Attention : on utilise colTexte + 1 car calculerCheminGrille utilise des index base 1
              cheminEvalue = calculerCheminGrille(donneesArbre, coord.row, colTexte + 1);
              estValide = true;
              valeursA[i][0] = cheminEvalue; 
              
              if (dictionnaireFeuilles.has(cheminEvalue)) {
                refCible = dictionnaireFeuilles.get(cheminEvalue).ref;
              } else {
                refCible = numVersLettreColonne(colTexte + 2) + coord.row; 
              }
              
            }
          }
        }
      }
      
      if (estValide) {
        if (cheminsExistants.has(cheminEvalue)) {
          couleursA[i][0] = COULEUR_VERT; 
        } else {
          if (couleursA[i][0] !== COULEUR_BLEU) {
            couleursA[i][0] = null; 
          }
          
          cheminsExistants.add(cheminEvalue);
          
          var valC = parseFloat(valeursC[i][0]);
          if (isNaN(valC)) valC = -Infinity; 
          
          infosExistants.set(cheminEvalue, { rowIndex: i, resultatC: valC });
        }
        
        formulesB[i][0] = "=" + nomFeuilleEchappe + "!" + refCible;
        
      } else {
        couleursA[i][0] = COULEUR_ROUGE; 
      }
    } else {
      if (couleursA[i][0] !== COULEUR_BLEU) {
        couleursA[i][0] = null;
      }
    }
  }
  
  // 4. Appliquer les modifications sur la feuille
  rangeColA.setValues(valeursA); 
  rangeColA.setBackgrounds(couleursA);
  rangeColB.setFormulas(formulesB);
  
  if (returnContext) {
    return {
      dictionnaireFeuilles: dictionnaireFeuilles,
      infosExistants: infosExistants,
      derniereLigneRemplie: derniereLigneRemplie,
      nomFeuilleEchappe: nomFeuilleEchappe
    };
  }
}


// --- FONCTION POUR TROUVER ET AJOUTER LE NOEUD MAXIMAL ---
function ajouterNoeudMax() {
  var contexte = remplacerIdParChemin(true);
  
  if (!contexte) return; 
  
  var dictionnaireFeuilles = contexte.dictionnaireFeuilles;
  var infosExistants = contexte.infosExistants;
  var nomFeuilleEchappe = contexte.nomFeuilleEchappe;
  
  var maxScore = -Infinity;
  var meilleurChemin = null;
  var meilleureRef = null;
  var ligneExistante = -1; 
  
  // 1. Déterminer le meilleur nœud (plus haute soustraction ou priorité)
  dictionnaireFeuilles.forEach(function(infos, cheminMap) {
    if (infos.isNumber) {
      var scoreCourant;
      var ligneOccurence = -1;
      
      if (infosExistants.has(cheminMap)) {
        scoreCourant = infosExistants.get(cheminMap).resultatC;
        ligneOccurence = infosExistants.get(cheminMap).rowIndex;
      } else {
        scoreCourant = infos.valeur;
      }
      
      if (scoreCourant > maxScore) {
        maxScore = scoreCourant;
        meilleurChemin = cheminMap;
        meilleureRef = infos.ref;
        ligneExistante = ligneOccurence;
      }
    }
  });
  
  var classeur = SpreadsheetApp.getActiveSpreadsheet();
  var feuilleTraitement = classeur.getActiveSheet(); 
  
  if (meilleurChemin !== null) {
    var lastCol = Math.max(feuilleTraitement.getLastColumn(), 3);
    
    // 2. Si le nœud maximal n'est pas déjà dans la colonne A
    if (ligneExistante === -1) {
      feuilleTraitement.insertRowBefore(LIGNE_DEBUT_VERIFICATION);
      
      feuilleTraitement.getRange(LIGNE_DEBUT_VERIFICATION, 1).setValue(meilleurChemin);
      feuilleTraitement.getRange(LIGNE_DEBUT_VERIFICATION, 2).setFormula("=" + nomFeuilleEchappe + "!" + meilleureRef);
      
      var currentLastRow = feuilleTraitement.getLastRow();
      if (currentLastRow > LIGNE_DEBUT_VERIFICATION) {
        var rangeToCopy = feuilleTraitement.getRange(LIGNE_DEBUT_VERIFICATION + 1, 3, 1, lastCol - 2);
        rangeToCopy.copyTo(feuilleTraitement.getRange(LIGNE_DEBUT_VERIFICATION, 3));
      }
      
      classeur.toast("Nouveau nœud ajouté. Tri en cours...");
      SpreadsheetApp.flush();
    } else {
      classeur.toast("Mise à jour de l'ordre du tableau...");
    }
    
    var newLastRow = feuilleTraitement.getLastRow();
    
    if (newLastRow >= LIGNE_DEBUT_VERIFICATION) {
      // 3. NETTOYAGE ET PREPARATION DU TRI
      var rangeA = feuilleTraitement.getRange(LIGNE_DEBUT_VERIFICATION, 1, newLastRow - LIGNE_DEBUT_VERIFICATION + 1, 1);
      var bgs = rangeA.getBackgrounds();
      var valsA = rangeA.getValues();
      var changedBg = false;
      
      var helperValues = []; // Valeurs pour la colonne de tri temporaire
      
      for (var k = 0; k < bgs.length; k++) {
        // Nettoyage de l'ancienne couleur bleue
        if (bgs[k][0] === COULEUR_BLEU) {
          bgs[k][0] = null;
          changedBg = true;
        }
        
        // Détermination de la validité
        var valA = valsA[k][0].toString().trim();
        if (valA === "") {
          helperValues.push([2]); // Lignes vides -> reléguées à la toute fin
        } else if (dictionnaireFeuilles.has(valA)) {
          helperValues.push([0]); // Lignes valides -> prioritaires (en haut)
        } else {
          helperValues.push([1]); // Lignes invalides (rouges) -> après les valides
        }
      }
      
      if (changedBg) {
        rangeA.setBackgrounds(bgs);
      }
      
      // 4. TRI MULTI-CRITÈRES AVEC COLONNE TEMPORAIRE
      lastCol = Math.max(feuilleTraitement.getLastColumn(), 3);
      feuilleTraitement.insertColumnAfter(lastCol); // Création de la colonne temporaire tout à droite
      var tempCol = lastCol + 1;
      
      // On écrit nos valeurs de validité (0, 1 ou 2) dans cette nouvelle colonne
      feuilleTraitement.getRange(LIGNE_DEBUT_VERIFICATION, tempCol, helperValues.length, 1).setValues(helperValues);
      
      // On trie tout le tableau incluant la colonne temporaire
      var rangeToSort = feuilleTraitement.getRange(LIGNE_DEBUT_VERIFICATION, 1, newLastRow - LIGNE_DEBUT_VERIFICATION + 1, tempCol);
      rangeToSort.sort([
        {column: tempCol, ascending: true}, // Critère 1 : Validité (0 d'abord, puis 1, puis 2)
        {column: 3, ascending: false}       // Critère 2 : Score (Soustraction décroissante)
      ]);
      
      // Suppression de la colonne temporaire (invisible pour l'utilisateur)
      feuilleTraitement.deleteColumn(tempCol);
    }
    
  } else {
    SpreadsheetApp.getUi().alert("Aucun nœud valide trouvé dans l'arbre avec une priorité numérique à droite.");
  }
}


// ---------------- FONCTIONS UTILITAIRES ---------------- //

function estFeuille(donnees, rowIdx, colIdx) {
  for (var r = rowIdx + 1; r < donnees.length; r++) {
    for (var c = 0; c < donnees[r].length; c++) {
      var val = donnees[r][c];
      if (val !== "" && typeof val === 'string' && val.trim() !== "") {
        if (c > colIdx) return false; 
        else return true; 
      }
    }
  }
  return true;
}

function calculerCheminGrille(donnees, ligneCible, colCible) {
  var rowIdx = ligneCible - 1;
  var colIdx = colCible - 1;
  var valeurNode = donnees[rowIdx][colIdx];
  var chemin = [valeurNode.trim()];
  var colCourante = colIdx;
  
  for (var r = rowIdx - 1; r >= 0; r--) {
    for (var c = colCourante - 1; c >= 0; c--) {
      if (c < donnees[r].length) {
        var val = donnees[r][c];
        if (val !== "" && typeof val === 'string' && val.trim() !== "") {
          chemin.unshift(val.trim());
          colCourante = c; 
          break; 
        }
      }
    }
    if (colCourante === 0) break; 
  }
  return chemin.join(" / ");
}

function numVersLettreColonne(colIdx) {
  var lettre = "";
  while (colIdx > 0) {
    var modulo = (colIdx - 1) % 26;
    lettre = String.fromCharCode(65 + modulo) + lettre;
    colIdx = Math.floor((colIdx - modulo) / 26);
  }
  return lettre;
}

function idVersCoordonnees(a1Notation) {
  var regex = /^([A-Za-z]+)([0-9]+)$/;
  var match = a1Notation.match(regex);
  if (!match) return null;
  
  var lettres = match[1].toUpperCase();
  var ligne = parseInt(match[2], 10);
  var colonne = 0;
  
  for (var i = 0; i < lettres.length; i++) {
    colonne = colonne * 26 + (lettres.charCodeAt(i) - 64);
  }
  return { row: ligne, col: colonne };
}